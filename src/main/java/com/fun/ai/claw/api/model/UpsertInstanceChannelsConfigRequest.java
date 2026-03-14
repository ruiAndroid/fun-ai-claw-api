package com.fun.ai.claw.api.model;

import java.util.List;

public record UpsertInstanceChannelsConfigRequest(
        Boolean cliEnabled,
        Long messageTimeoutSecs,
        Boolean dingtalkEnabled,
        String dingtalkClientId,
        String dingtalkClientSecret,
        List<String> dingtalkAllowedUsers,
        Boolean qqEnabled,
        String qqAppId,
        String qqAppSecret,
        List<String> qqAllowedUsers,
        String updatedBy
) {
}
