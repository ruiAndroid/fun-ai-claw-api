package com.fun.ai.claw.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.agent-guidance")
public class AgentGuidanceProperties {

    private String defaultMainAgentsMdPath = "";
    private String defaultMainAgentsMd = "";
    private boolean overwriteOnStart = true;
    private String instanceWorkspacePath = "/zeroclaw-data/workspace/AGENTS.md";
    private int maxBytes = 262144;

    public String getDefaultMainAgentsMdPath() {
        return defaultMainAgentsMdPath;
    }

    public void setDefaultMainAgentsMdPath(String defaultMainAgentsMdPath) {
        this.defaultMainAgentsMdPath = defaultMainAgentsMdPath;
    }

    public String getDefaultMainAgentsMd() {
        return defaultMainAgentsMd;
    }

    public void setDefaultMainAgentsMd(String defaultMainAgentsMd) {
        this.defaultMainAgentsMd = defaultMainAgentsMd;
    }

    public boolean isOverwriteOnStart() {
        return overwriteOnStart;
    }

    public void setOverwriteOnStart(boolean overwriteOnStart) {
        this.overwriteOnStart = overwriteOnStart;
    }

    public String getInstanceWorkspacePath() {
        return instanceWorkspacePath;
    }

    public void setInstanceWorkspacePath(String instanceWorkspacePath) {
        this.instanceWorkspacePath = instanceWorkspacePath;
    }

    public int getMaxBytes() {
        return maxBytes;
    }

    public void setMaxBytes(int maxBytes) {
        this.maxBytes = maxBytes;
    }
}
