package com.fun.ai.claw.api.config;

import com.fun.ai.claw.api.service.TerminalWebSocketHandler;
import com.fun.ai.claw.api.service.UiControllerWebSocketProxyHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class TerminalWebSocketConfig implements WebSocketConfigurer {

    private final TerminalWebSocketHandler terminalWebSocketHandler;
    private final UiControllerWebSocketProxyHandler uiControllerWebSocketProxyHandler;

    public TerminalWebSocketConfig(TerminalWebSocketHandler terminalWebSocketHandler,
                                   UiControllerWebSocketProxyHandler uiControllerWebSocketProxyHandler) {
        this.terminalWebSocketHandler = terminalWebSocketHandler;
        this.uiControllerWebSocketProxyHandler = uiControllerWebSocketProxyHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(terminalWebSocketHandler, "/v1/terminal/ws")
                .setAllowedOriginPatterns("*");

        registry.addHandler(
                        uiControllerWebSocketProxyHandler,
                        "/fun-claw/ui-controller/*/ws/**",
                        "/ui-controller/*/ws/**",
                        "/ws/**"
                )
                .setAllowedOriginPatterns("*");
    }
}

