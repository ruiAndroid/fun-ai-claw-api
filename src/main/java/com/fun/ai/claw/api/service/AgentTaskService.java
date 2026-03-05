package com.fun.ai.claw.api.service;

import com.fun.ai.claw.api.model.AgentTaskConfirmResponse;
import com.fun.ai.claw.api.model.AgentTaskPrepareRequest;
import com.fun.ai.claw.api.model.AgentTaskPrepareResponse;
import com.fun.ai.claw.api.model.AgentTaskResponse;
import com.fun.ai.claw.api.model.ClawInstanceDto;
import com.fun.ai.claw.api.repository.AgentTaskRepository;
import com.fun.ai.claw.api.repository.InstanceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class AgentTaskService {

    private final InstanceRepository instanceRepository;
    private final AgentTaskRepository repository;
    private final TaskExecutor taskExecutor;
    private final RestClient restClient;
    private final String gatewayUrlTemplate;
    private final int confirmTtlSeconds;
    private final String dispatchMode;

    public AgentTaskService(InstanceRepository instanceRepository,
                            AgentTaskRepository repository,
                            TaskExecutor taskExecutor,
                            @Value("${app.gateway.url-template:http://127.0.0.1/fun-claw/ui-controller/{instanceId}}")
                            String gatewayUrlTemplate,
                            @Value("${app.agent-task.confirm-ttl-seconds:600}") int confirmTtlSeconds,
                            @Value("${app.agent-task.dispatch-mode:force_delegate}") String dispatchMode,
                            @Value("${app.agent-task.webhook-timeout-seconds:90}") int webhookTimeoutSeconds) {
        this.instanceRepository = instanceRepository;
        this.repository = repository;
        this.taskExecutor = taskExecutor;
        this.gatewayUrlTemplate = gatewayUrlTemplate;
        this.confirmTtlSeconds = Math.max(confirmTtlSeconds, 60);
        this.dispatchMode = normalizeDispatchMode(dispatchMode);

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(10));
        requestFactory.setReadTimeout(Duration.ofSeconds(Math.max(webhookTimeoutSeconds, 10)));

        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }

    public AgentTaskPrepareResponse prepare(AgentTaskPrepareRequest request) {
        validateInstanceExists(request.instanceId());
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(confirmTtlSeconds);
        UUID taskId = UUID.randomUUID();
        String confirmToken = UUID.randomUUID().toString().replace("-", "");
        String requestMessage = buildRequestMessage(request);
        String summary = "agentId=" + request.agentId() + ", messageLength=" + request.message().length();
        repository.insertPrepared(taskId, confirmToken, request, requestMessage, expiresAt, now);
        return new AgentTaskPrepareResponse(taskId, confirmToken, summary, expiresAt);
    }

    public AgentTaskConfirmResponse confirm(String confirmToken) {
        if (!StringUtils.hasText(confirmToken)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "confirmToken must not be blank");
        }
        Instant now = Instant.now();
        AgentTaskRepository.TaskRow task = repository.claimPrepared(confirmToken.trim(), now)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "confirmToken invalid, expired, or already used"));

        taskExecutor.execute(() -> runTask(task));
        return new AgentTaskConfirmResponse(task.taskId(), "QUEUED", now);
    }

    public AgentTaskResponse getTask(UUID taskId) {
        return repository.findTask(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "task not found"));
    }

    private void runTask(AgentTaskRepository.TaskRow task) {
        Instant runningAt = Instant.now();
        repository.markRunning(task.taskId(), runningAt);
        try {
            URI webhookUri = URI.create(resolveWebhookUrl(task.instanceId()));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("message", task.requestMessage());

            String body = restClient.post()
                    .uri(webhookUri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(String.class);

            repository.markSucceeded(task.taskId(), body == null ? "" : body, Instant.now());
        } catch (RestClientResponseException ex) {
            String details = ex.getResponseBodyAsString();
            String message = "webhook failed: HTTP " + ex.getStatusCode().value()
                    + (StringUtils.hasText(details) ? " " + details : "");
            repository.markFailed(task.taskId(), message, Instant.now());
        } catch (ResourceAccessException ex) {
            repository.markFailed(task.taskId(), "webhook timeout or network error: " + ex.getMessage(), Instant.now());
        } catch (Exception ex) {
            repository.markFailed(task.taskId(), "execution error: " + ex.getMessage(), Instant.now());
        }
    }

    private void validateInstanceExists(UUID instanceId) {
        Optional<ClawInstanceDto> instance = instanceRepository.findById(instanceId);
        if (instance.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "instance not found");
        }
    }

    private String resolveWebhookUrl(UUID instanceId) {
        ClawInstanceDto instance = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "instance not found"));
        String port = instance.gatewayHostPort() == null ? "" : String.valueOf(instance.gatewayHostPort());
        String base = gatewayUrlTemplate
                .replace("{instanceId}", instance.id().toString())
                .replace("{port}", port);
        String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return trimmed + "/webhook";
    }

    private String buildRequestMessage(AgentTaskPrepareRequest request) {
        if ("force_delegate".equals(dispatchMode)) {
            return buildForceDelegateMessage(request);
        }
        return request.message();
    }

    private String buildForceDelegateMessage(AgentTaskPrepareRequest request) {
        return """
                请通过 delegate 工具将任务交给指定子智能体执行。
                规则：
                1) 只调用一次 delegate 工具。
                2) agent 必须是 "%s"。
                3) 禁止递归委托（不能委托回自身）。
                4) 返回子智能体结果，不要额外发挥。
                任务内容：
                %s
                """.formatted(request.agentId(), request.message());
    }

    private String normalizeDispatchMode(String mode) {
        if (!StringUtils.hasText(mode)) {
            return "force_delegate";
        }
        String normalized = mode.trim().toLowerCase();
        return switch (normalized) {
            case "force_delegate", "plain" -> normalized;
            default -> "force_delegate";
        };
    }
}
