package com.fun.ai.claw.api.service;

import com.fun.ai.claw.api.model.AgentTaskConfirmResponse;
import com.fun.ai.claw.api.model.AgentTaskPrepareRequest;
import com.fun.ai.claw.api.model.AgentTaskPrepareResponse;
import com.fun.ai.claw.api.model.AgentTaskResponse;
import com.fun.ai.claw.api.model.ClawInstanceDto;
import com.fun.ai.claw.api.model.PairingCodeResponse;
import com.fun.ai.claw.api.repository.AgentTaskRepository;
import com.fun.ai.claw.api.repository.InstanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
    private static final Logger log = LoggerFactory.getLogger(AgentTaskService.class);
    private static final String WS_TOKEN_QUERY_PARAM = "token";
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
    private static final Pattern OUTPUT_TEXT_FIELD_PATTERN =
            Pattern.compile("(?is)\"output_text\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern RESULT_FIELD_PATTERN =
            Pattern.compile("(?is)\"result\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern TEXT_FIELD_PATTERN =
            Pattern.compile("(?is)\"text\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern DELTA_FIELD_PATTERN =
            Pattern.compile("(?is)\"delta\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern MESSAGE_CONTENT_FIELD_PATTERN =
            Pattern.compile("(?is)\"message\"\\s*:\\s*\\{[\\s\\S]*?\"content\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

    private final InstanceRepository instanceRepository;
    private final PairingCodeService pairingCodeService;
    private final AgentTaskRepository repository;
    private final TaskExecutor taskExecutor;
    private final RestClient restClient;
    private final HttpClient wsHttpClient;
    private final String gatewayUrlTemplate;
    private final int confirmTtlSeconds;
    private final String dispatchMode;
    private final boolean preferWsChat;
    private final boolean preferApiChat;
    private final boolean allowWebhookFallback;
    private final String authTokenQueryParam;
    private final int wsChatTimeoutSeconds;

    public AgentTaskService(InstanceRepository instanceRepository,
                            PairingCodeService pairingCodeService,
                            AgentTaskRepository repository,
                            TaskExecutor taskExecutor,
                            @Value("${app.gateway.url-template:http://127.0.0.1/fun-claw/ui-controller/{instanceId}}")
                            String gatewayUrlTemplate,
                            @Value("${app.agent-task.confirm-ttl-seconds:600}") int confirmTtlSeconds,
                            @Value("${app.agent-task.dispatch-mode:force_delegate}") String dispatchMode,
                            @Value("${app.agent-task.prefer-ws-chat:true}") boolean preferWsChat,
                            @Value("${app.agent-task.prefer-api-chat:true}") boolean preferApiChat,
                            @Value("${app.agent-task.allow-webhook-fallback:true}") boolean allowWebhookFallback,
                            @Value("${app.pairing-code.auth-token-query-param:authToken}") String authTokenQueryParam,
                            @Value("${app.agent-task.ws-chat-timeout-seconds:120}") int wsChatTimeoutSeconds,
                            @Value("${app.agent-task.webhook-timeout-seconds:90}") int webhookTimeoutSeconds) {
        this.instanceRepository = instanceRepository;
        this.pairingCodeService = pairingCodeService;
        this.repository = repository;
        this.taskExecutor = taskExecutor;
        this.gatewayUrlTemplate = gatewayUrlTemplate;
        this.confirmTtlSeconds = Math.max(confirmTtlSeconds, 60);
        this.dispatchMode = normalizeDispatchMode(dispatchMode);
        this.preferWsChat = preferWsChat;
        this.preferApiChat = preferApiChat;
        this.allowWebhookFallback = allowWebhookFallback;
        this.authTokenQueryParam = StringUtils.hasText(authTokenQueryParam) ? authTokenQueryParam.trim() : "authToken";
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
            InvocationOutcome outcome = invokeGateway(task);
            InvocationResult result = outcome.result();
            String routeTrace = outcome.routeTrace();
            String responseText = result.responseText();
            if (!StringUtils.hasText(responseText)) {
                String rawPreview = truncate(result.rawBody(), 500);
                String details = "empty response from gateway (" + result.source() + ")"
                        + (StringUtils.hasText(rawPreview) ? ", rawPreview=" + rawPreview : "");
                repository.markFailed(
                        task.taskId(),
                        withRouteTrace(details, routeTrace),
                        Instant.now()
                );
                return;
            }
            if (looksLikeRawToolCall(responseText)) {
                String preview = truncate(responseText, 300);
                repository.markFailed(
                        task.taskId(),
                        withRouteTrace("model returned raw tool-call markup; tools may not have executed. source="
                                + result.source() + ", preview=" + preview, routeTrace),
                        Instant.now()
                );
                return;
            }
            repository.markSucceeded(task.taskId(), responseText, Instant.now());
        } catch (IllegalStateException ex) {
            repository.markFailed(task.taskId(), ex.getMessage(), Instant.now());
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

    private InvocationOutcome invokeGateway(AgentTaskRepository.TaskRow task) {
        List<String> attempts = new ArrayList<>();
        String authToken = resolveGatewayAuthToken(task.instanceId());
        attempts.add(StringUtils.hasText(authToken) ? "auth=present" : "auth=none");

        if (preferWsChat) {
            try {
                InvocationResult result = invokeWsChat(task, authToken);
                attempts.add("ws_chat=ok");
                return new InvocationOutcome(result, attemptsSummary(attempts));
            } catch (Exception ex) {
                String reason = compactError(ex);
                attempts.add("ws_chat=fail(" + reason + ")");
                log.warn("agent-task ws_chat failed, taskId={}, instanceId={}, reason={}",
                        task.taskId(), task.instanceId(), reason);
            }
        } else {
            attempts.add("ws_chat=disabled");
        }

        if (preferApiChat) {
            try {
                InvocationResult result = invokeApiChat(task, authToken);
                attempts.add("api_chat=ok");
                return new InvocationOutcome(result, attemptsSummary(attempts));
            } catch (RestClientResponseException ex) {
                int status = ex.getStatusCode().value();
                String bodyPreview = truncate(ex.getResponseBodyAsString(), 200);
                attempts.add("api_chat=http_" + status);
                if (status != 404 && status != 405 && !allowWebhookFallback) {
                    throw new IllegalStateException(
                            "api_chat failed and webhook fallback is disabled. attempts="
                                    + attemptsSummary(attempts)
                                    + (StringUtils.hasText(bodyPreview) ? ", body=" + bodyPreview : ""),
                            ex
                    );
                }
                if (status != 404 && status != 405) {
                    log.warn("agent-task api_chat failed, taskId={}, instanceId={}, status={}, body={}",
                            task.taskId(), task.instanceId(), status, bodyPreview);
                }
            } catch (Exception ex) {
                String reason = compactError(ex);
                attempts.add("api_chat=fail(" + reason + ")");
                if (!allowWebhookFallback) {
                    throw new IllegalStateException(
                            "api_chat failed and webhook fallback is disabled. attempts=" + attemptsSummary(attempts),
                            ex
                    );
                }
                log.warn("agent-task api_chat failed, taskId={}, instanceId={}, reason={}",
                        task.taskId(), task.instanceId(), reason);
            }
        } else {
            attempts.add("api_chat=disabled");
        }

        if (allowWebhookFallback) {
            try {
                InvocationResult result = invokeWebhook(task, authToken);
                attempts.add("webhook=ok");
                return new InvocationOutcome(result, attemptsSummary(attempts));
            } catch (Exception ex) {
                String reason = compactError(ex);
                attempts.add("webhook=fail(" + reason + ")");
                throw new IllegalStateException("all gateway routes failed. attempts=" + attemptsSummary(attempts), ex);
            }
        }

        throw new IllegalStateException(
                "no gateway route succeeded and webhook fallback is disabled. attempts=" + attemptsSummary(attempts)
        );
    }

    private InvocationResult invokeApiChat(AgentTaskRepository.TaskRow task, String authToken) {
        URI apiChatUri = URI.create(resolveApiChatUrl(task.instanceId()));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", task.requestMessage());

        RestClient.RequestBodySpec request = restClient.post()
                .uri(apiChatUri)
                .contentType(MediaType.APPLICATION_JSON);
        request = applyOptionalBearerAuth(request, authToken);

        String body = request
                .body(payload)
                .retrieve()
                .body(String.class);

        String response = extractResponseField(body);
        return new InvocationResult(response, body == null ? "" : body, "api_chat");
    }

    private InvocationResult invokeWsChat(AgentTaskRepository.TaskRow task, String authToken) {
        String wsChatUrl = resolveWsChatUrl(task.instanceId());
        wsChatUrl = appendQueryParam(wsChatUrl, WS_TOKEN_QUERY_PARAM, authToken);
        URI wsChatUri = URI.create(wsChatUrl);
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

    private InvocationResult invokeWebhook(AgentTaskRepository.TaskRow task, String authToken) {
        URI webhookUri = URI.create(resolveWebhookUrl(task.instanceId()));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", task.requestMessage());

        RestClient.RequestBodySpec request = restClient.post()
                .uri(webhookUri)
                .contentType(MediaType.APPLICATION_JSON);
        request = applyOptionalBearerAuth(request, authToken);

        String body = request
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

    private String extractFirstJsonField(String json, Pattern... patterns) {
        if (!StringUtils.hasText(json) || patterns == null || patterns.length == 0) {
            return "";
        }
        for (Pattern pattern : patterns) {
            String value = extractJsonField(json, pattern);
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return "";
    }

    private RestClient.RequestBodySpec applyOptionalBearerAuth(RestClient.RequestBodySpec request, String authToken) {
        if (!StringUtils.hasText(authToken)) {
            return request;
        }
        return request.header("Authorization", "Bearer " + authToken.trim());
    }

    private String resolveGatewayAuthToken(UUID instanceId) {
        try {
            PairingCodeResponse pairingCode = pairingCodeService.fetchPairingCode(instanceId);
            if (pairingCode == null || !StringUtils.hasText(pairingCode.pairingLink())) {
                return null;
            }
            String pairingLink = pairingCode.pairingLink().trim();
            String token = extractQueryParam(pairingLink, authTokenQueryParam);
            if (!StringUtils.hasText(token)) {
                token = extractQueryParam(pairingLink, WS_TOKEN_QUERY_PARAM);
            }
            return StringUtils.hasText(token) ? token.trim() : null;
        } catch (Exception ex) {
            log.warn("agent-task failed to resolve gateway auth token, instanceId={}, reason={}",
                    instanceId, compactError(ex));
            return null;
        }
    }

    private String extractQueryParam(String url, String paramName) {
        if (!StringUtils.hasText(url) || !StringUtils.hasText(paramName)) {
            return null;
        }
        try {
            URI uri = URI.create(url);
            String query = uri.getRawQuery();
            if (!StringUtils.hasText(query)) {
                return null;
            }
            String expected = paramName.trim();
            for (String pair : query.split("&")) {
                if (!StringUtils.hasText(pair)) {
                    continue;
                }
                int index = pair.indexOf('=');
                String name = index >= 0 ? pair.substring(0, index) : pair;
                String value = index >= 0 ? pair.substring(index + 1) : "";
                String decodedName = URLDecoder.decode(name, StandardCharsets.UTF_8);
                if (!expected.equals(decodedName)) {
                    continue;
                }
                String decodedValue = URLDecoder.decode(value, StandardCharsets.UTF_8);
                return StringUtils.hasText(decodedValue) ? decodedValue : null;
            }
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String appendQueryParam(String baseUrl, String paramName, String paramValue) {
        if (!StringUtils.hasText(baseUrl)
                || !StringUtils.hasText(paramName)
                || !StringUtils.hasText(paramValue)) {
            return baseUrl;
        }
        String separator = baseUrl.contains("?") ? "&" : "?";
        String encodedName = URLEncoder.encode(paramName, StandardCharsets.UTF_8);
        String encodedValue = URLEncoder.encode(paramValue, StandardCharsets.UTF_8);
        return baseUrl + separator + encodedName + "=" + encodedValue;
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

    private String compactError(Throwable throwable) {
        if (throwable == null) {
            return "unknown";
        }
        String message = throwable.getMessage();
        if (!StringUtils.hasText(message) && throwable.getCause() != null) {
            message = throwable.getCause().getMessage();
        }
        if (!StringUtils.hasText(message)) {
            message = throwable.getClass().getSimpleName();
        }
        String normalized = message.replace('\n', ' ').replace('\r', ' ').trim();
        return truncate(normalized, 180);
    }

    private String withRouteTrace(String baseMessage, String routeTrace) {
        if (!StringUtils.hasText(routeTrace)) {
            return baseMessage;
        }
        return baseMessage + ", attempts=" + routeTrace;
    }

    private String attemptsSummary(List<String> attempts) {
        if (attempts == null || attempts.isEmpty()) {
            return "";
        }
        return String.join(" -> ", attempts);
    }

    private final class WsChatListener implements WebSocket.Listener {
        private final StringBuilder frameBuffer = new StringBuilder();
        private final StringBuilder chunkBuffer = new StringBuilder();
        private final StringBuilder toolResultBuffer = new StringBuilder();
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
                } else if (toolResultBuffer.length() > 0) {
                    completion.complete(toolResultBuffer.toString());
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
                case "chunk", "delta", "response.output_text.delta", "response.output.delta" ->
                        appendNormalized(chunkBuffer, extractChunkText(message));
                case "tool_result" -> appendNormalized(toolResultBuffer, extractToolResultText(message));
                case "response.output_text.done", "response.output.done" ->
                        appendNormalized(chunkBuffer, extractTerminalResponseText(message));
                case "message", "done" -> {
                    String response = extractTerminalResponseText(message);
                    if (!completion.isDone()) {
                        completion.complete(response);
                    }
                }
                case "response.completed", "response.done" -> {
                    String response = extractTerminalResponseText(message);
                    if (!completion.isDone()) {
                        completion.complete(response);
                    }
                }
                case "response.failed" -> {
                    String error = extractFirstJsonField(
                            message,
                            MESSAGE_FIELD_PATTERN,
                            RESULT_FIELD_PATTERN,
                            RESPONSE_FIELD_PATTERN,
                            TEXT_FIELD_PATTERN
                    );
                    if (!StringUtils.hasText(error)) {
                        error = "ws chat response failed";
                    }
                    if (!completion.isDone()) {
                        completion.completeExceptionally(new RuntimeException(error));
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
                    // Ignore tool_call/unknown events; wait for done/message.
                }
            }
        }

        private String extractChunkText(String message) {
            return extractFirstJsonField(
                    message,
                    CONTENT_FIELD_PATTERN,
                    DELTA_FIELD_PATTERN,
                    RESPONSE_FIELD_PATTERN,
                    OUTPUT_TEXT_FIELD_PATTERN,
                    TEXT_FIELD_PATTERN
            );
        }

        private String extractToolResultText(String message) {
            return extractFirstJsonField(
                    message,
                    RESULT_FIELD_PATTERN,
                    RESPONSE_FIELD_PATTERN,
                    OUTPUT_TEXT_FIELD_PATTERN,
                    MESSAGE_CONTENT_FIELD_PATTERN,
                    CONTENT_FIELD_PATTERN,
                    TEXT_FIELD_PATTERN
            );
        }

        private String extractTerminalResponseText(String message) {
            String response = extractFirstJsonField(
                    message,
                    FULL_RESPONSE_FIELD_PATTERN,
                    OUTPUT_TEXT_FIELD_PATTERN,
                    RESPONSE_FIELD_PATTERN,
                    RESULT_FIELD_PATTERN,
                    MESSAGE_CONTENT_FIELD_PATTERN,
                    CONTENT_FIELD_PATTERN,
                    TEXT_FIELD_PATTERN
            );
            if (!StringUtils.hasText(response)) {
                response = chunkBuffer.toString();
            }
            if (!StringUtils.hasText(response)) {
                response = toolResultBuffer.toString();
            }
            return response;
        }

        private void appendNormalized(StringBuilder builder, String text) {
            if (!StringUtils.hasText(text)) {
                return;
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(text);
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

    private record InvocationOutcome(InvocationResult result, String routeTrace) {
    }
}
