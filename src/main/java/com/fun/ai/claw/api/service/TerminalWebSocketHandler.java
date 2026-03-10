package com.fun.ai.claw.api.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TerminalWebSocketHandler extends AbstractPlaneWebSocketProxyHandler {

    public TerminalWebSocketHandler(@Value("${app.plane.base-url:http://127.0.0.1:8090/internal/v1}") String planeBaseUrl,
                                    @Value("${app.plane.ws-connect-timeout-seconds:10}") long wsConnectTimeoutSeconds,
                                    @Value("${app.plane.ws-keepalive-seconds:25}") long wsKeepaliveSeconds) {
        super(planeBaseUrl, "/terminal/ws", wsConnectTimeoutSeconds, wsKeepaliveSeconds);
    }
}
