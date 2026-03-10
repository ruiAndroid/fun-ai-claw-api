package com.fun.ai.claw.api.service;

import jakarta.annotation.PreDestroy;
import com.fun.ai.claw.api.model.OpenSessionRecord;
import org.springframework.boot.json.JsonParseException;
import org.springframework.boot.json.JsonParser;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Component
public class OpenAgentSessionWebSocketHandler extends AbstractWebSocketHandler {

    private final WebSocketClient webSocketClient;
    private final URI planeBaseUri;
    private final String planeWsScheme;
    private final Duration connectTimeout;
    private final Duration keepaliveInterval;
    private final ScheduledExecutorService keepaliveExecutor;
    private final OpenSessionService openSessionService;
    private final JsonParser jsonParser = JsonParserFactory.getJsonParser();
    private final Map<String, ProxyContext> contexts = new ConcurrentHashMap<>();

    public OpenAgentSessionWebSocketHandler(OpenSessionService openSessionService,
                                            @org.springframework.beans.factory.annotation.Value("${app.plane.base-url:http://127.0.0.1:8090/internal/v1}") String planeBaseUrl,
                                            @org.springframework.beans.factory.annotation.Value("${app.plane.ws-connect-timeout-seconds:10}") long wsConnectTimeoutSeconds,
                                            @org.springframework.beans.factory.annotation.Value("${app.plane.ws-keepalive-seconds:25}") long wsKeepaliveSeconds) {
        this.openSessionService = openSessionService;
        this.webSocketClient = new StandardWebSocketClient();
        this.planeBaseUri = URI.create(requirePlaneBaseUrl(planeBaseUrl));
        this.planeWsScheme = normalizeWsScheme(this.planeBaseUri.getScheme());
        this.connectTimeout = Duration.ofSeconds(Math.max(1, wsConnectTimeoutSeconds));
        this.keepaliveInterval = wsKeepaliveSeconds > 0 ? Duration.ofSeconds(wsKeepaliveSeconds) : Duration.ZERO;
        this.keepaliveExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "fun-ai-claw-open-ws-keepalive");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession clientSession) {
        UUID sessionId;
        String wsToken;
        try {
            sessionId = parseSessionId(clientSession.getUri());
            wsToken = parseWsToken(clientSession.getUri());
        } catch (ResponseStatusException ex) {
            safeClose(clientSession, CloseStatus.BAD_DATA);
            return;
        }

        OpenSessionRecord openSession;
        try {
            openSession = openSessionService.validateWebsocketSession(sessionId, wsToken);
        } catch (ResponseStatusException ex) {
            safeClose(clientSession, ex.getStatusCode().value() == HttpStatus.UNAUTHORIZED.value()
                    ? CloseStatus.POLICY_VIOLATION
                    : CloseStatus.BAD_DATA);
            return;
        }

        ProxyContext context = new ProxyContext(clientSession, openSession);
        contexts.put(clientSession.getId(), context);

        CompletableFuture<WebSocketSession> upstreamFuture = webSocketClient.execute(
                new UpstreamBridgeHandler(clientSession.getId()),
                new WebSocketHttpHeaders(),
                buildPlaneTargetUri(openSession)
        );
        context.setUpstreamFuture(upstreamFuture);
        upstreamFuture.orTimeout(connectTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .whenComplete((unused, throwable) -> {
                    if (throwable != null) {
                        closeAndCleanup(clientSession.getId(), CloseStatus.SERVER_ERROR);
                    }
                });
        scheduleKeepalive(context, clientSession.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession clientSession, TextMessage message) {
        ProxyContext context = contexts.get(clientSession.getId());
        if (context == null) {
            return;
        }
        openSessionService.recordUserInput(context.openSession().id(), message.getPayload());
        forwardToUpstream(clientSession.getId(), message);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession clientSession, BinaryMessage message) {
        forwardToUpstream(clientSession.getId(), new BinaryMessage(copyPayload(message)));
    }

    @Override
    protected void handlePongMessage(WebSocketSession clientSession, PongMessage message) {
        forwardToUpstream(clientSession.getId(), new PongMessage(copyPayload(message)));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        closeAndCleanup(session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        closeAndCleanup(session.getId(), CloseStatus.SERVER_ERROR);
    }

    @PreDestroy
    public void shutdownKeepaliveExecutor() {
        keepaliveExecutor.shutdownNow();
    }

    private void recordUpstreamFrame(ProxyContext context, String payload) {
        if (context == null || !StringUtils.hasText(payload)) {
            return;
        }
        try {
            Map<String, Object> parsed = jsonParser.parseMap(payload);
            Object eventTypeValue = parsed.get("eventType");
            if (!(eventTypeValue instanceof String eventType) || !"message".equals(eventType)) {
                return;
            }
            Object messageObject = parsed.get("message");
            if (!(messageObject instanceof Map<?, ?> messageMap)) {
                return;
            }
            String role = stringValue(messageMap.get("role"));
            String content = stringValue(messageMap.get("content"));
            String thinkingContent = stringValue(messageMap.get("thinkingContent"));
            String providerMessageId = stringValue(messageMap.get("messageId"));
            Long providerSequence = longValue(messageMap.get("sequence"));
            boolean pending = booleanValue(messageMap.get("pending"));
            Instant emittedAt = instantValue(stringValue(messageMap.get("emittedAt")));
            openSessionService.recordRuntimeMessage(
                    context.openSession().id(),
                    eventType,
                    role,
                    content,
                    thinkingContent,
                    messageMap.get("interaction"),
                    payload,
                    providerMessageId,
                    providerSequence,
                    pending,
                    emittedAt
            );
        } catch (JsonParseException ignored) {
            // Ignore non-frame payloads.
        }
    }

    private String stringValue(Object value) {
        return value instanceof String text ? text : null;
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            return Boolean.parseBoolean(text.trim());
        }
        return false;
    }

    private Instant instantValue(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private void forwardToUpstream(String clientSessionId, org.springframework.web.socket.WebSocketMessage<?> message) {
        ProxyContext context = contexts.get(clientSessionId);
        if (context == null) {
            return;
        }
        WebSocketSession upstreamSession = context.upstreamSession();
        if (upstreamSession == null || !upstreamSession.isOpen()) {
            return;
        }
        try {
            synchronized (upstreamSession) {
                upstreamSession.sendMessage(message);
            }
        } catch (IOException ex) {
            closeAndCleanup(clientSessionId, CloseStatus.SERVER_ERROR);
        }
    }

    private void forwardToClient(String clientSessionId, org.springframework.web.socket.WebSocketMessage<?> message) {
        ProxyContext context = contexts.get(clientSessionId);
        if (context == null) {
            return;
        }
        WebSocketSession clientSession = context.clientSession();
        if (clientSession == null || !clientSession.isOpen()) {
            return;
        }
        if (message instanceof TextMessage textMessage) {
            recordUpstreamFrame(context, textMessage.getPayload());
            if (!shouldForwardTextPayloadToClient(textMessage.getPayload())) {
                return;
            }
        }
        try {
            synchronized (clientSession) {
                clientSession.sendMessage(message);
            }
        } catch (IOException ex) {
            closeAndCleanup(clientSessionId, CloseStatus.SERVER_ERROR);
        }
    }

    private boolean shouldForwardTextPayloadToClient(String payload) {
        if (!StringUtils.hasText(payload)) {
            return true;
        }
        try {
            Map<String, Object> parsed = jsonParser.parseMap(payload);
            Object eventTypeValue = parsed.get("eventType");
            if (!(eventTypeValue instanceof String eventType)) {
                return true;
            }
            return !"debug".equalsIgnoreCase(eventType.trim());
        } catch (JsonParseException ignored) {
            return true;
        }
    }

    private void closeAndCleanup(String clientSessionId, CloseStatus status) {
        ProxyContext context = contexts.remove(clientSessionId);
        if (context == null) {
            return;
        }

        ScheduledFuture<?> keepaliveTask = context.keepaliveTask();
        if (keepaliveTask != null) {
            keepaliveTask.cancel(true);
        }

        CompletableFuture<WebSocketSession> upstreamFuture = context.upstreamFuture();
        if (upstreamFuture != null && !upstreamFuture.isDone()) {
            upstreamFuture.cancel(true);
        }

        safeClose(context.upstreamSession(), status);
        safeClose(context.clientSession(), status);
    }

    private void safeClose(WebSocketSession session, CloseStatus status) {
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            session.close(status);
        } catch (IOException ignored) {
            // Ignore close failures.
        }
    }

    private void scheduleKeepalive(ProxyContext context, String clientSessionId) {
        if (context == null || keepaliveInterval.isZero() || keepaliveInterval.isNegative()) {
            return;
        }
        ScheduledFuture<?> task = keepaliveExecutor.scheduleAtFixedRate(
                () -> sendKeepalive(clientSessionId),
                keepaliveInterval.toSeconds(),
                keepaliveInterval.toSeconds(),
                TimeUnit.SECONDS
        );
        context.setKeepaliveTask(task);
    }

    private void sendKeepalive(String clientSessionId) {
        ProxyContext context = contexts.get(clientSessionId);
        if (context == null) {
            return;
        }
        ByteBuffer payload = ByteBuffer.wrap(Long.toString(System.currentTimeMillis()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (!sendPing(context.clientSession(), payload.duplicate()) || !sendPing(context.upstreamSession(), payload.duplicate())) {
            closeAndCleanup(clientSessionId, CloseStatus.SESSION_NOT_RELIABLE);
        }
    }

    private boolean sendPing(WebSocketSession session, ByteBuffer payload) {
        if (session == null || !session.isOpen()) {
            return false;
        }
        try {
            synchronized (session) {
                session.sendMessage(new PingMessage(payload));
            }
            return true;
        } catch (IOException ex) {
            return false;
        }
    }

    private UUID parseSessionId(URI inboundUri) {
        String raw = resolveSingleQueryParam(inboundUri, "sessionId")
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "sessionId is required"));
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sessionId must be a valid UUID");
        }
    }

    private String parseWsToken(URI inboundUri) {
        return resolveSingleQueryParam(inboundUri, "wsToken")
                .filter(StringUtils::hasText)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "wsToken is required"));
    }

    private Optional<String> resolveSingleQueryParam(URI inboundUri, String name) {
        if (inboundUri == null || !StringUtils.hasText(name)) {
            return Optional.empty();
        }
        return Optional.ofNullable(UriComponentsBuilder.fromUri(inboundUri).build().getQueryParams().getFirst(name));
    }

    private URI buildPlaneTargetUri(OpenSessionRecord session) {
        UriComponentsBuilder builder = UriComponentsBuilder.newInstance()
                .scheme(planeWsScheme)
                .host(planeBaseUri.getHost())
                .port(planeBaseUri.getPort())
                .path(planeBaseUri.getPath())
                .path("/agent-session/ws")
                .queryParam("instanceId", session.instanceId());
        if (StringUtils.hasText(session.agentId())) {
            builder.queryParam("agentId", session.agentId());
        }
        return builder.build(true).toUri();
    }

    private String requirePlaneBaseUrl(String planeBaseUrl) {
        if (!StringUtils.hasText(planeBaseUrl)) {
            throw new IllegalArgumentException("app.plane.base-url must not be blank");
        }
        return planeBaseUrl.trim();
    }

    private String normalizeWsScheme(String scheme) {
        if (!StringUtils.hasText(scheme)) {
            return "ws";
        }
        return switch (scheme.trim().toLowerCase()) {
            case "http", "ws" -> "ws";
            case "https", "wss" -> "wss";
            default -> throw new IllegalArgumentException("app.plane.base-url must use http/https/ws/wss");
        };
    }

    private ByteBuffer copyPayload(BinaryMessage message) {
        ByteBuffer payload = message.getPayload();
        ByteBuffer duplicate = payload.duplicate();
        ByteBuffer copy = ByteBuffer.allocate(duplicate.remaining());
        copy.put(duplicate);
        copy.flip();
        return copy;
    }

    private ByteBuffer copyPayload(PongMessage message) {
        ByteBuffer payload = message.getPayload();
        ByteBuffer duplicate = payload.duplicate();
        ByteBuffer copy = ByteBuffer.allocate(duplicate.remaining());
        copy.put(duplicate);
        copy.flip();
        return copy;
    }

    private final class UpstreamBridgeHandler extends AbstractWebSocketHandler {
        private final String clientSessionId;

        private UpstreamBridgeHandler(String clientSessionId) {
            this.clientSessionId = clientSessionId;
        }

        @Override
        public void afterConnectionEstablished(WebSocketSession upstreamSession) {
            ProxyContext context = contexts.get(clientSessionId);
            if (context == null) {
                safeClose(upstreamSession, CloseStatus.NORMAL);
                return;
            }
            context.setUpstreamSession(upstreamSession);
        }

        @Override
        protected void handleTextMessage(WebSocketSession upstreamSession, TextMessage message) {
            forwardToClient(clientSessionId, message);
        }

        @Override
        protected void handleBinaryMessage(WebSocketSession upstreamSession, BinaryMessage message) {
            forwardToClient(clientSessionId, new BinaryMessage(copyPayload(message)));
        }

        @Override
        protected void handlePongMessage(WebSocketSession upstreamSession, PongMessage message) {
            forwardToClient(clientSessionId, new PongMessage(copyPayload(message)));
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            closeAndCleanup(clientSessionId, status);
        }

        @Override
        public void handleTransportError(WebSocketSession session, Throwable exception) {
            closeAndCleanup(clientSessionId, CloseStatus.SERVER_ERROR);
        }
    }

    private static final class ProxyContext {
        private final WebSocketSession clientSession;
        private final OpenSessionRecord openSession;
        private volatile CompletableFuture<WebSocketSession> upstreamFuture;
        private volatile WebSocketSession upstreamSession;
        private volatile ScheduledFuture<?> keepaliveTask;

        private ProxyContext(WebSocketSession clientSession, OpenSessionRecord openSession) {
            this.clientSession = clientSession;
            this.openSession = openSession;
        }

        public WebSocketSession clientSession() {
            return clientSession;
        }

        public OpenSessionRecord openSession() {
            return openSession;
        }

        public CompletableFuture<WebSocketSession> upstreamFuture() {
            return upstreamFuture;
        }

        public void setUpstreamFuture(CompletableFuture<WebSocketSession> upstreamFuture) {
            this.upstreamFuture = upstreamFuture;
        }

        public WebSocketSession upstreamSession() {
            return upstreamSession;
        }

        public void setUpstreamSession(WebSocketSession upstreamSession) {
            this.upstreamSession = upstreamSession;
        }

        public ScheduledFuture<?> keepaliveTask() {
            return keepaliveTask;
        }

        public void setKeepaliveTask(ScheduledFuture<?> keepaliveTask) {
            this.keepaliveTask = keepaliveTask;
        }
    }
}
