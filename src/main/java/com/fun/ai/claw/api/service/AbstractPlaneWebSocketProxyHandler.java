package com.fun.ai.claw.api.service;

import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

abstract class AbstractPlaneWebSocketProxyHandler extends AbstractWebSocketHandler {

    private static final Set<String> FORWARDED_HANDSHAKE_HEADERS = Set.of(
            "authorization",
            "cookie",
            "origin",
            "referer",
            "sec-websocket-protocol",
            "user-agent"
    );

    private final WebSocketClient webSocketClient;
    private final URI planeBaseUri;
    private final String planeWsScheme;
    private final String upstreamPath;
    private final Duration connectTimeout;
    private final Map<String, ProxyContext> contexts = new ConcurrentHashMap<>();

    protected AbstractPlaneWebSocketProxyHandler(String planeBaseUrl,
                                                 String upstreamPath,
                                                 long wsConnectTimeoutSeconds) {
        this.webSocketClient = new StandardWebSocketClient();
        this.planeBaseUri = URI.create(requirePlaneBaseUrl(planeBaseUrl));
        this.planeWsScheme = normalizeWsScheme(this.planeBaseUri.getScheme());
        this.upstreamPath = normalizeUpstreamPath(upstreamPath);
        long timeoutSeconds = wsConnectTimeoutSeconds > 0 ? wsConnectTimeoutSeconds : 10;
        this.connectTimeout = Duration.ofSeconds(timeoutSeconds);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession clientSession) {
        ProxyContext context = new ProxyContext(clientSession);
        contexts.put(clientSession.getId(), context);

        CompletableFuture<WebSocketSession> upstreamFuture = webSocketClient.execute(
                new UpstreamBridgeHandler(clientSession.getId()),
                copyHandshakeHeaders(resolveHandshakeHeaders(clientSession)),
                buildTargetUri(clientSession.getUri())
        );
        context.setUpstreamFuture(upstreamFuture);
        upstreamFuture.orTimeout(connectTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .whenComplete((unused, throwable) -> {
                    if (throwable != null) {
                        closeAndCleanup(clientSession.getId(), CloseStatus.SERVER_ERROR);
                    }
                });
    }

    private HttpHeaders resolveHandshakeHeaders(WebSocketSession session) {
        Object snapshot = session.getAttributes().get(WebSocketHandshakeSnapshotInterceptor.ATTR_HANDSHAKE_HEADERS);
        if (snapshot instanceof HttpHeaders headers) {
            return headers;
        }
        return HttpHeaders.copyOf(session.getHandshakeHeaders());
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

    private URI buildTargetUri(URI inboundUri) {
        StringBuilder uriBuilder = new StringBuilder()
                .append(planeWsScheme)
                .append("://")
                .append(planeBaseUri.getHost());
        if (planeBaseUri.getPort() >= 0) {
            uriBuilder.append(":").append(planeBaseUri.getPort());
        }
        String basePath = planeBaseUri.getPath();
        if (StringUtils.hasText(basePath)) {
            if (basePath.endsWith("/")) {
                uriBuilder.append(basePath, 0, basePath.length() - 1);
            } else {
                uriBuilder.append(basePath);
            }
        }
        uriBuilder.append(upstreamPath);
        if (inboundUri != null && StringUtils.hasText(inboundUri.getRawQuery())) {
            uriBuilder.append("?").append(inboundUri.getRawQuery());
        }
        return URI.create(uriBuilder.toString());
    }

    private WebSocketHttpHeaders copyHandshakeHeaders(HttpHeaders inboundHeaders) {
        WebSocketHttpHeaders outboundHeaders = new WebSocketHttpHeaders();
        for (Map.Entry<String, List<String>> entry : inboundHeaders.headerSet()) {
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

    private String requirePlaneBaseUrl(String planeBaseUrl) {
        if (!StringUtils.hasText(planeBaseUrl)) {
            throw new IllegalArgumentException("app.plane.base-url must not be blank");
        }
        return planeBaseUrl.trim();
    }

    private String normalizeUpstreamPath(String upstreamPath) {
        if (!StringUtils.hasText(upstreamPath)) {
            throw new IllegalArgumentException("upstream path must not be blank");
        }
        String normalizedPath = upstreamPath.trim();
        return normalizedPath.startsWith("/") ? normalizedPath : "/" + normalizedPath;
    }

    private String normalizeWsScheme(String scheme) {
        if (!StringUtils.hasText(scheme)) {
            return "ws";
        }
        String normalized = scheme.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "http", "ws" -> "ws";
            case "https", "wss" -> "wss";
            default -> throw new IllegalArgumentException("app.plane.base-url must use http/https/ws/wss");
        };
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
