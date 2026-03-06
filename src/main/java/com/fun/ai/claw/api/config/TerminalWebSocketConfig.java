package com.fun.ai.claw.api.config;

import com.fun.ai.claw.api.service.AgentSessionWebSocketHandler;
import com.fun.ai.claw.api.service.TerminalWebSocketHandler;
import com.fun.ai.claw.api.service.UiControllerHandshakeSnapshotInterceptor;
import com.fun.ai.claw.api.service.UiControllerWebSocketProxyHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class TerminalWebSocketConfig implements WebSocketConfigurer {

    private final AgentSessionWebSocketHandler agentSessionWebSocketHandler;
    private final TerminalWebSocketHandler terminalWebSocketHandler;
    private final UiControllerWebSocketProxyHandler uiControllerWebSocketProxyHandler;
    private final UiControllerHandshakeSnapshotInterceptor uiControllerHandshakeSnapshotInterceptor;

    public TerminalWebSocketConfig(AgentSessionWebSocketHandler agentSessionWebSocketHandler,
                                   TerminalWebSocketHandler terminalWebSocketHandler,
                                   UiControllerWebSocketProxyHandler uiControllerWebSocketProxyHandler,
                                   UiControllerHandshakeSnapshotInterceptor uiControllerHandshakeSnapshotInterceptor) {
        this.agentSessionWebSocketHandler = agentSessionWebSocketHandler;
        this.terminalWebSocketHandler = terminalWebSocketHandler;
        this.uiControllerWebSocketProxyHandler = uiControllerWebSocketProxyHandler;
        this.uiControllerHandshakeSnapshotInterceptor = uiControllerHandshakeSnapshotInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(agentSessionWebSocketHandler, "/v1/agent-session/ws")
                .setAllowedOriginPatterns("*");

        registry.addHandler(terminalWebSocketHandler, "/v1/terminal/ws")
                .setAllowedOriginPatterns("*");

        registry.addHandler(
                        uiControllerWebSocketProxyHandler,
                        "/fun-claw/ui-controller/*/ws/**",
                        "/ui-controller/*/ws/**",
                        "/ws/**"
                )
                .addInterceptors(uiControllerHandshakeSnapshotInterceptor)
                .setAllowedOriginPatterns("*");
    }
}

