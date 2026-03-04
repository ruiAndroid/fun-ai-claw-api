package com.fun.ai.claw.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.llm-gateway")
public class LlmGatewayProperties {

    private String baseUrl = "https://api.ai.fun.tv/v1";
    private String authToken = "";
    private String authScheme = "Bearer";
    private int timeoutSeconds = 120;
    private boolean preferIncomingAuthorization = true;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getAuthToken() {
        return authToken;
    }

    public void setAuthToken(String authToken) {
        this.authToken = authToken;
    }

    public String getAuthScheme() {
        return authScheme;
    }

    public void setAuthScheme(String authScheme) {
        this.authScheme = authScheme;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public boolean isPreferIncomingAuthorization() {
        return preferIncomingAuthorization;
    }

    public void setPreferIncomingAuthorization(boolean preferIncomingAuthorization) {
        this.preferIncomingAuthorization = preferIncomingAuthorization;
    }
}

