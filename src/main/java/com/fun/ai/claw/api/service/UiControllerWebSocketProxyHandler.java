package com.fun.ai.claw.api.service;

import com.fun.ai.claw.api.model.ClawInstanceDto;
import com.fun.ai.claw.api.repository.InstanceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class UiControllerWebSocketProxyHandler extends AbstractWebSocketHandler {

    private static final Pattern PREFIXED_WS_PATH = Pattern.compile(
            "^/(?:fun-claw/ui-controller|ui-controller)/([0-9a-fA-F\\-]{36})(/ws(?:/.*)?)$"
    );
    private static final Pattern ROOT_WS_PATH = Pattern.compile("^/ws(?:/.*)?$");
    private static final Pattern REFERER_INSTANCE_PATH = Pattern.compile(
            "^/(?:fun-claw/ui-controller|ui-controller)/([0-9a-fA-F\\-]{36})(?:/.*)?$"
    );
    private static final Set<String> FORWARDED_HANDSHAKE_HEADERS = Set.of(
            "authorization",
            "cookie",
            "sec-websocket-protocol",
            "user-agent"
    );

    private final InstanceRepository instanceRepository;
    private final WebSocketClient webSocketClient;
    private final String upstreamHost;
    private final String upstreamWsScheme;
    private final Duration connectTimeout;
    private final Map<String, ProxyContext> contexts = new ConcurrentHashMap<>();

    public UiControllerWebSocketProxyHandler(InstanceRepository instanceRepository,
                                             @Value("${app.ui-controller.upstream-scheme:http}") String upstreamScheme,
                                             @Value("${app.ui-controller.upstream-host:127.0.0.1}") String upstreamHost,
                                             @Value("${app.ui-controller.ws-connect-timeout-seconds:10}") long wsConnectTimeoutSeconds) {
        this.instanceRepository = instanceRepository;
        this.webSocketClient = new StandardWebSocketClient();
        this.upstreamHost = requireHost(upstreamHost);
        this.upstreamWsScheme = normalizeWsScheme(upstreamScheme);
        long timeoutSeconds = wsConnectTimeoutSeconds > 0 ? wsConnectTimeoutSeconds : 10;
        this.connectTimeout = Duration.ofSeconds(timeoutSeconds);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession clientSession) throws Exception {
        Optional<RouteTarget> routeTarget = resolveRouteTarget(clientSession);
        if (routeTarget.isEmpty()) {
            safeClose(clientSession, CloseStatus.BAD_DATA);
            return;
        }

        Optional<ClawInstanceDto> instance = instanceRepository.findById(routeTarget.get().instanceId());
        if (instance.isEmpty() || instance.get().gatewayHostPort() == null) {
            safeClose(clientSession, CloseStatus.POLICY_VIOLATION);
            return;
        }

        Integer targetPort = instance.get().gatewayHostPort();
        if (targetPort <= 0 || targetPort > 65535) {
            safeClose(clientSession, CloseStatus.SERVER_ERROR);
            return;
        }

        URI targetUri = buildTargetUri(targetPort, routeTarget.get().downstreamPath(), clientSession.getUri());
        HttpHeaders outboundHeaders = copyHandshakeHeaders(clientSession.getHandshakeHeaders());

        ProxyContext context = new ProxyContext(clientSession);
        contexts.put(clientSession.getId(), context);

        CompletableFuture<WebSocketSession> upstreamFuture = webSocketClient.execute(
                new UpstreamBridgeHandler(clientSession.getId()),
                outboundHeaders,
                targetUri
        );
        context.setUpstreamFuture(upstreamFuture);
        upstreamFuture.orTimeout(connectTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .exceptionally(ex -> {
                    closeAndCleanup(clientSession.getId(), CloseStatus.SERVER_ERROR);
                    return null;
                });
    }

    @Override
    protected void handleTextMessage(WebSocketSession clientSession, TextMessage message) {
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

    private Optional<RouteTarget> resolveRouteTarget(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null || !StringUtils.hasText(uri.getPath())) {
            return Optional.empty();
        }

        String path = uri.getPath();
        Matcher prefixed = PREFIXED_WS_PATH.matcher(path);
        if (prefixed.matches()) {
            try {
                return Optional.of(new RouteTarget(
                        UUID.fromString(prefixed.group(1)),
                        prefixed.group(2)
                ));
            } catch (IllegalArgumentException ex) {
                return Optional.empty();
            }
        }

        if (!ROOT_WS_PATH.matcher(path).matches()) {
            return Optional.empty();
        }

        Optional<UUID> refererInstanceId = resolveInstanceIdFromReferer(session.getHandshakeHeaders().getFirst("Referer"));
        if (refererInstanceId.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new RouteTarget(refererInstanceId.get(), path));
    }

    private Optional<UUID> resolveInstanceIdFromReferer(String referer) {
        if (!StringUtils.hasText(referer)) {
            return Optional.empty();
        }
        try {
            URI refererUri = URI.create(referer);
            String refererPath = refererUri.getPath();
            if (!StringUtils.hasText(refererPath)) {
                return Optional.empty();
            }
            Matcher matcher = REFERER_INSTANCE_PATH.matcher(refererPath);
            if (!matcher.matches()) {
                return Optional.empty();
            }
            return Optional.of(UUID.fromString(matcher.group(1)));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private URI buildTargetUri(int targetPort, String downstreamPath, URI inboundUri) {
        StringBuilder uriBuilder = new StringBuilder()
                .append(upstreamWsScheme)
                .append("://")
                .append(upstreamHost)
                .append(":")
                .append(targetPort)
                .append(downstreamPath);

        if (inboundUri != null && StringUtils.hasText(inboundUri.getRawQuery())) {
            uriBuilder.append("?").append(inboundUri.getRawQuery());
        }
        return URI.create(uriBuilder.toString());
    }

    private HttpHeaders copyHandshakeHeaders(HttpHeaders inboundHeaders) {
        HttpHeaders outboundHeaders = new HttpHeaders();
        for (Map.Entry<String, List<String>> entry : inboundHeaders.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            String lowerHeader = entry.getKey().toLowerCase(Locale.ROOT);
            if (!FORWARDED_HANDSHAKE_HEADERS.contains(lowerHeader)) {
                continue;
            }
            for (String value : entry.getValue()) {
                outboundHeaders.add(entry.getKey(), value);
            }
        }
        return outboundHeaders;
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
        try {
            synchronized (clientSession) {
                clientSession.sendMessage(message);
            }
        } catch (IOException ex) {
            closeAndCleanup(clientSessionId, CloseStatus.SERVER_ERROR);
        }
    }

    private void closeAndCleanup(String clientSessionId, CloseStatus status) {
        ProxyContext context = contexts.remove(clientSessionId);
        if (context == null) {
            return;
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

    private String normalizeWsScheme(String scheme) {
        if (!StringUtils.hasText(scheme)) {
            return "ws";
        }
        String normalized = scheme.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "http", "ws" -> "ws";
            case "https", "wss" -> "wss";
            default -> throw new IllegalArgumentException("app.ui-controller.upstream-scheme must be http/https/ws/wss");
        };
    }

    private String requireHost(String host) {
        if (!StringUtils.hasText(host)) {
            throw new IllegalArgumentException("app.ui-controller.upstream-host must not be blank");
        }
        return host.trim();
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
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            forwardToClient(clientSessionId, message);
        }

        @Override
        protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
            forwardToClient(clientSessionId, new BinaryMessage(copyPayload(message)));
        }

        @Override
        protected void handlePongMessage(WebSocketSession session, PongMessage message) {
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

    private record RouteTarget(UUID instanceId, String downstreamPath) {
    }

    private static final class ProxyContext {
        private final WebSocketSession clientSession;
        private volatile WebSocketSession upstreamSession;
        private volatile CompletableFuture<WebSocketSession> upstreamFuture;

        private ProxyContext(WebSocketSession clientSession) {
            this.clientSession = clientSession;
        }

        private WebSocketSession clientSession() {
            return clientSession;
        }

        private WebSocketSession upstreamSession() {
            return upstreamSession;
        }

        private void setUpstreamSession(WebSocketSession upstreamSession) {
            this.upstreamSession = upstreamSession;
        }

        private CompletableFuture<WebSocketSession> upstreamFuture() {
            return upstreamFuture;
        }

        private void setUpstreamFuture(CompletableFuture<WebSocketSession> upstreamFuture) {
            this.upstreamFuture = upstreamFuture;
        }
    }
}

