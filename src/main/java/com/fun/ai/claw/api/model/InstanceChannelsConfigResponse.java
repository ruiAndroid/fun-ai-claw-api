package com.fun.ai.claw.api.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record InstanceChannelsConfigResponse(
        UUID instanceId,
        String runtimeConfigPath,
        String source,
        boolean overwriteOnStart,
        boolean overrideExists,
        boolean cliEnabled,
        long messageTimeoutSecs,
        boolean dingtalkEnabled,
        String dingtalkClientId,
        String dingtalkClientSecret,
        List<String> dingtalkAllowedUsers,
        boolean qqEnabled,
        String qqAppId,
        String qqAppSecret,
        List<String> qqAllowedUsers,
        Instant overrideUpdatedAt,
        String overrideUpdatedBy
) {
}
