package com.fun.ai.claw.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.instance-config")
public class InstanceConfigProperties {

    private String defaultConfigTomlPath = "classpath:templates/default-instance-config.toml";
    private String defaultConfigToml = "";
    private boolean overwriteOnStart = true;
    private String runtimeConfigPath = "/data/zeroclaw/config.toml";
    private int maxBytes = 524288;

    public String getDefaultConfigTomlPath() {
        return defaultConfigTomlPath;
    }

    public void setDefaultConfigTomlPath(String defaultConfigTomlPath) {
        this.defaultConfigTomlPath = defaultConfigTomlPath;
    }

    public String getDefaultConfigToml() {
        return defaultConfigToml;
    }

    public void setDefaultConfigToml(String defaultConfigToml) {
        this.defaultConfigToml = defaultConfigToml;
    }

    public boolean isOverwriteOnStart() {
        return overwriteOnStart;
    }

    public void setOverwriteOnStart(boolean overwriteOnStart) {
        this.overwriteOnStart = overwriteOnStart;
    }

    public String getRuntimeConfigPath() {
        return runtimeConfigPath;
    }

    public void setRuntimeConfigPath(String runtimeConfigPath) {
        this.runtimeConfigPath = runtimeConfigPath;
    }

    public int getMaxBytes() {
        return maxBytes;
    }

    public void setMaxBytes(int maxBytes) {
        this.maxBytes = maxBytes;
    }
}
