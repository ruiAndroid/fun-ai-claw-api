package com.fun.ai.claw.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.open")
public class OpenApiProperties {

    private String allowedOrigins = "*";
    private final Auth auth = new Auth();
    private final Session session = new Session();

    public String getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(String allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    public Auth getAuth() {
        return auth;
    }

    public Session getSession() {
        return session;
    }

    public static class Auth {
        private String appIdHeader = "X-Open-App-Id";
        private String appSecretHeader = "X-Open-App-Secret";

        public String getAppIdHeader() {
            return appIdHeader;
        }

        public void setAppIdHeader(String appIdHeader) {
            this.appIdHeader = appIdHeader;
        }

        public String getAppSecretHeader() {
            return appSecretHeader;
        }

        public void setAppSecretHeader(String appSecretHeader) {
            this.appSecretHeader = appSecretHeader;
        }
    }

    public static class Session {
        private long wsTokenTtlSeconds = 86400;

        public long getWsTokenTtlSeconds() {
            return wsTokenTtlSeconds;
        }

        public void setWsTokenTtlSeconds(long wsTokenTtlSeconds) {
            this.wsTokenTtlSeconds = wsTokenTtlSeconds;
        }
    }
}
