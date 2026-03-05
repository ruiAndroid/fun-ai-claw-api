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
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AgentTaskService {
    private static final Pattern RESPONSE_FIELD_PATTERN =
            Pattern.compile("(?is)\"response\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern TYPE_FIELD_PATTERN =
            Pattern.compile("(?is)\"type\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern CONTENT_FIELD_PATTERN =
            Pattern.compile("(?is)\"content\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern FULL_RESPONSE_FIELD_PATTERN =
            Pattern.compile("(?is)\"full_response\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern MESSAGE_FIELD_PATTERN =
            Pattern.compile("(?is)\"message\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

    private final InstanceRepository instanceRepository;
    private final AgentTaskRepository repository;
    private final TaskExecutor taskExecutor;
    private final RestClient restClient;
    private final HttpClient wsHttpClient;
    private final String gatewayUrlTemplate;
    private final int confirmTtlSeconds;
    private final String dispatchMode;
    private final boolean preferWsChat;
    private final boolean preferApiChat;
    private final int wsChatTimeoutSeconds;

    public AgentTaskService(InstanceRepository instanceRepository,
                            AgentTaskRepository repository,
                            TaskExecutor taskExecutor,
                            @Value("${app.gateway.url-template:http://127.0.0.1/fun-claw/ui-controller/{instanceId}}")
                            String gatewayUrlTemplate,
                            @Value("${app.agent-task.confirm-ttl-seconds:600}") int confirmTtlSeconds,
                            @Value("${app.agent-task.dispatch-mode:force_delegate}") String dispatchMode,
                            @Value("${app.agent-task.prefer-ws-chat:true}") boolean preferWsChat,
                            @Value("${app.agent-task.prefer-api-chat:true}") boolean preferApiChat,
                            @Value("${app.agent-task.ws-chat-timeout-seconds:120}") int wsChatTimeoutSeconds,
                            @Value("${app.agent-task.webhook-timeout-seconds:90}") int webhookTimeoutSeconds) {
        this.instanceRepository = instanceRepository;
        this.repository = repository;
        this.taskExecutor = taskExecutor;
        this.gatewayUrlTemplate = gatewayUrlTemplate;
        this.confirmTtlSeconds = Math.max(confirmTtlSeconds, 60);
        this.dispatchMode = normalizeDispatchMode(dispatchMode);
        this.preferWsChat = preferWsChat;
        this.preferApiChat = preferApiChat;
        this.wsChatTimeoutSeconds = Math.max(wsChatTimeoutSeconds, 10);

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(10));
        requestFactory.setReadTimeout(Duration.ofSeconds(Math.max(webhookTimeoutSeconds, 10)));

        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .build();
        this.wsHttpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
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
            InvocationResult result = invokeGateway(task);
            String responseText = result.responseText();
            if (!StringUtils.hasText(responseText)) {
                repository.markFailed(task.taskId(), "empty response from gateway (" + result.source() + ")", Instant.now());
                return;
            }
            if (looksLikeRawToolCall(responseText)) {
                String preview = truncate(responseText, 300);
                repository.markFailed(
                        task.taskId(),
                        "model returned raw tool-call markup; tools may not have executed. source=" + result.source() + ", preview=" + preview,
                        Instant.now()
                );
                return;
            }
            repository.markSucceeded(task.taskId(), responseText, Instant.now());
        } catch (RestClientResponseException ex) {
            String details = ex.getResponseBodyAsString();
            String message = "gateway call failed: HTTP " + ex.getStatusCode().value()
                    + (StringUtils.hasText(details) ? " " + truncate(details, 1000) : "");
            repository.markFailed(task.taskId(), message, Instant.now());
        } catch (ResourceAccessException ex) {
            repository.markFailed(task.taskId(), "gateway timeout or network error: " + ex.getMessage(), Instant.now());
        } catch (Exception ex) {
            repository.markFailed(task.taskId(), "execution error: " + ex.getMessage(), Instant.now());
        }
    }

    private InvocationResult invokeGateway(AgentTaskRepository.TaskRow task) {
        if (preferWsChat) {
            try {
                return invokeWsChat(task);
            } catch (Exception ignored) {
                // Fallback to HTTP endpoints if websocket chat is unavailable.
            }
        }
        if (preferApiChat) {
            try {
                return invokeApiChat(task);
            } catch (RestClientResponseException ex) {
                int status = ex.getStatusCode().value();
                if (status == 404 || status == 405) {
                    return invokeWebhook(task);
                }
                throw ex;
            }
        }
        return invokeWebhook(task);
    }

    private InvocationResult invokeApiChat(AgentTaskRepository.TaskRow task) {
        URI apiChatUri = URI.create(resolveApiChatUrl(task.instanceId()));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", task.requestMessage());

        String body = restClient.post()
                .uri(apiChatUri)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(String.class);

        String response = extractResponseField(body);
        return new InvocationResult(response, body == null ? "" : body, "api_chat");
    }

    private InvocationResult invokeWsChat(AgentTaskRepository.TaskRow task) {
        URI wsChatUri = URI.create(resolveWsChatUrl(task.instanceId()));
        WsChatListener listener = new WsChatListener();
        WebSocket webSocket = wsHttpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(wsChatUri, listener)
                .join();

        String payload = "{\"type\":\"message\",\"content\":\"" + escapeJson(task.requestMessage()) + "\"}";
        webSocket.sendText(payload, true).join();
        String response;
        try {
            response = listener.await(Duration.ofSeconds(wsChatTimeoutSeconds));
        } catch (TimeoutException ex) {
            webSocket.abort();
            throw new RuntimeException("ws chat timeout after " + wsChatTimeoutSeconds + "s");
        }
        webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        return new InvocationResult(response, listener.rawEvents(), "ws_chat");
    }

    private InvocationResult invokeWebhook(AgentTaskRepository.TaskRow task) {
        URI webhookUri = URI.create(resolveWebhookUrl(task.instanceId()));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", task.requestMessage());

        String body = restClient.post()
                .uri(webhookUri)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(String.class);

        String response = extractResponseField(body);
        return new InvocationResult(response, body == null ? "" : body, "webhook");
    }

    private void validateInstanceExists(UUID instanceId) {
        Optional<ClawInstanceDto> instance = instanceRepository.findById(instanceId);
        if (instance.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "instance not found");
        }
    }

    private String resolveWebhookUrl(UUID instanceId) {
        String base = resolveGatewayBaseUrl(instanceId);
        return base + "/webhook";
    }

    private String resolveApiChatUrl(UUID instanceId) {
        String base = resolveGatewayBaseUrl(instanceId);
        return base + "/api/chat";
    }

    private String resolveWsChatUrl(UUID instanceId) {
        String base = resolveGatewayBaseUrl(instanceId);
        String wsBase;
        if (base.startsWith("https://")) {
            wsBase = "wss://" + base.substring("https://".length());
        } else if (base.startsWith("http://")) {
            wsBase = "ws://" + base.substring("http://".length());
        } else if (base.startsWith("wss://") || base.startsWith("ws://")) {
            wsBase = base;
        } else {
            wsBase = "ws://" + base;
        }
        return wsBase + "/ws/chat";
    }

    private String resolveGatewayBaseUrl(UUID instanceId) {
        ClawInstanceDto instance = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "instance not found"));
        String port = instance.gatewayHostPort() == null ? "" : String.valueOf(instance.gatewayHostPort());
        String base = gatewayUrlTemplate
                .replace("{instanceId}", instance.id().toString())
                .replace("{port}", port);
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private String buildRequestMessage(AgentTaskPrepareRequest request) {
        if ("force_delegate".equals(dispatchMode)) {
            return buildForceDelegateMessage(request);
        }
        return request.message();
    }

    private String buildForceDelegateMessage(AgentTaskPrepareRequest request) {
        return """
                You must call the delegate tool exactly once to hand off work to a sub-agent.
                Rules:
                1) Call delegate only once.
                2) The delegate agent must be "%s".
                3) Do not recursively delegate back to the same agent.
                4) Return only the sub-agent result.
                Task:
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

    private String extractResponseField(String body) {
        if (!StringUtils.hasText(body)) {
            return "";
        }
        Matcher matcher = RESPONSE_FIELD_PATTERN.matcher(body);
        if (matcher.find()) {
            return unescapeJsonString(matcher.group(1));
        }
        return body;
    }

    private String unescapeJsonString(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\\\", "\\")
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t");
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String extractJsonField(String json, Pattern pattern) {
        if (!StringUtils.hasText(json)) {
            return "";
        }
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            return "";
        }
        return unescapeJsonString(matcher.group(1));
    }

    private boolean looksLikeRawToolCall(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        String lower = text.toLowerCase();
        return lower.contains("<function_calls")
                || lower.contains("<tool_call")
                || lower.contains("\"tool_calls\"")
                || lower.contains("\"function_calls\"");
    }

    private String truncate(String value, int maxLength) {
        if (!StringUtils.hasText(value) || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }

    private final class WsChatListener implements WebSocket.Listener {
        private final StringBuilder frameBuffer = new StringBuilder();
        private final StringBuilder chunkBuffer = new StringBuilder();
        private final StringBuilder rawEvents = new StringBuilder();
        private final CompletableFuture<String> completion = new CompletableFuture<>();

        @Override
        public void onOpen(WebSocket webSocket) {
            WebSocket.Listener.super.onOpen(webSocket);
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            frameBuffer.append(data);
            if (last) {
                String message = frameBuffer.toString();
                frameBuffer.setLength(0);
                if (rawEvents.length() > 0) {
                    rawEvents.append('\n');
                }
                rawEvents.append(message);
                handleWsMessage(message);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (!completion.isDone()) {
                completion.completeExceptionally(error);
            }
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (!completion.isDone()) {
                if (chunkBuffer.length() > 0) {
                    completion.complete(chunkBuffer.toString());
                } else {
                    completion.completeExceptionally(new RuntimeException(
                            "ws chat closed before response, status=" + statusCode + ", reason=" + reason));
                }
            }
            return CompletableFuture.completedFuture(null);
        }

        private void handleWsMessage(String message) {
            String type = extractJsonField(message, TYPE_FIELD_PATTERN).toLowerCase();
            switch (type) {
                case "chunk" -> chunkBuffer.append(extractJsonField(message, CONTENT_FIELD_PATTERN));
                case "message", "done" -> {
                    String response = extractJsonField(message, FULL_RESPONSE_FIELD_PATTERN);
                    if (!StringUtils.hasText(response)) {
                        response = extractJsonField(message, CONTENT_FIELD_PATTERN);
                    }
                    if (!StringUtils.hasText(response)) {
                        response = chunkBuffer.toString();
                    }
                    if (!completion.isDone()) {
                        completion.complete(response);
                    }
                }
                case "error" -> {
                    String error = extractJsonField(message, MESSAGE_FIELD_PATTERN);
                    if (!StringUtils.hasText(error)) {
                        error = "ws chat returned error event";
                    }
                    if (!completion.isDone()) {
                        completion.completeExceptionally(new RuntimeException(error));
                    }
                }
                default -> {
                    // Ignore tool_call/tool_result/unknown events; wait for done/message.
                }
            }
        }

        String await(Duration timeout) throws TimeoutException {
            try {
                return completion.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new RuntimeException(ex.getMessage(), ex);
            }
        }

        String rawEvents() {
            return rawEvents.toString();
        }
    }

    private record InvocationResult(String responseText, String rawBody, String source) {
    }
}
