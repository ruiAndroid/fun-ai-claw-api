package com.fun.ai.claw.api.service;

import com.fun.ai.claw.api.model.ClawInstanceDto;
import com.fun.ai.claw.api.model.MgcNovelToScriptConfirmResponse;
import com.fun.ai.claw.api.model.MgcNovelToScriptPrepareRequest;
import com.fun.ai.claw.api.model.MgcNovelToScriptPrepareResponse;
import com.fun.ai.claw.api.model.MgcNovelToScriptTaskResponse;
import com.fun.ai.claw.api.repository.InstanceRepository;
import com.fun.ai.claw.api.repository.MgcNovelToScriptRepository;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.core.task.TaskExecutor;
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
public class MgcNovelToScriptService {

    private final InstanceRepository instanceRepository;
    private final MgcNovelToScriptRepository repository;
    private final TaskExecutor taskExecutor;
    private final RestClient restClient;
    private final String gatewayUrlTemplate;
    private final int confirmTtlSeconds;

    public MgcNovelToScriptService(InstanceRepository instanceRepository,
                                   MgcNovelToScriptRepository repository,
                                   TaskExecutor taskExecutor,
                                   @Value("${app.gateway.url-template:http://127.0.0.1/fun-claw/ui-controller/{instanceId}}") String gatewayUrlTemplate,
                                   @Value("${app.mgc-novel-to-script.confirm-ttl-seconds:600}") int confirmTtlSeconds,
                                   @Value("${app.mgc-novel-to-script.webhook-timeout-seconds:90}") int webhookTimeoutSeconds) {
        this.instanceRepository = instanceRepository;
        this.repository = repository;
        this.taskExecutor = taskExecutor;
        this.gatewayUrlTemplate = gatewayUrlTemplate;
        this.confirmTtlSeconds = Math.max(confirmTtlSeconds, 60);

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(10));
        requestFactory.setReadTimeout(Duration.ofSeconds(Math.max(webhookTimeoutSeconds, 10)));

        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }

    public MgcNovelToScriptPrepareResponse prepare(MgcNovelToScriptPrepareRequest request) {
        validateInstanceExists(request.instanceId());
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(confirmTtlSeconds);
        UUID taskId = UUID.randomUUID();
        String confirmToken = UUID.randomUUID().toString().replace("-", "");
        String requestMessage = buildRequestMessage(request);
        String summary = buildSummary(request);
        repository.insertPrepared(taskId, confirmToken, request, requestMessage, expiresAt, now);
        return new MgcNovelToScriptPrepareResponse(taskId, confirmToken, summary, expiresAt);
    }

    public MgcNovelToScriptConfirmResponse confirm(String confirmToken) {
        if (!StringUtils.hasText(confirmToken)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "confirmToken must not be blank");
        }
        Instant now = Instant.now();
        MgcNovelToScriptRepository.TaskRow task = repository.claimPrepared(confirmToken.trim(), now)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "confirmToken invalid, expired, or already used"));

        taskExecutor.execute(() -> runTask(task));
        return new MgcNovelToScriptConfirmResponse(task.taskId(), "QUEUED", now);
    }

    public MgcNovelToScriptTaskResponse getTask(UUID taskId) {
        return repository.findTask(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "task not found"));
    }

    private void runTask(MgcNovelToScriptRepository.TaskRow task) {
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

    private String buildRequestMessage(MgcNovelToScriptPrepareRequest request) {
        return "剧本类内容：" + request.scriptContent()
                + "；剧本类型：" + request.scriptType()
                + "；受众：" + request.targetAudience()
                + "；期望集数：" + request.expectedEpisodeCount();
    }

    private String buildSummary(MgcNovelToScriptPrepareRequest request) {
        return "剧本类型=" + request.scriptType()
                + "，受众=" + request.targetAudience()
                + "，期望集数=" + request.expectedEpisodeCount();
    }
}
