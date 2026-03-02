package com.fun.ai.claw.api.config;

import com.fun.ai.claw.api.service.TerminalWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class TerminalWebSocketConfig implements WebSocketConfigurer {

    private final TerminalWebSocketHandler terminalWebSocketHandler;

    public TerminalWebSocketConfig(TerminalWebSocketHandler terminalWebSocketHandler) {
        this.terminalWebSocketHandler = terminalWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(terminalWebSocketHandler, "/v1/terminal/ws")
                .setAllowedOriginPatterns("*");
    }
}

