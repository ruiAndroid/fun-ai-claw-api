package com.fun.ai.claw.api.model;

import java.util.Map;
import java.util.UUID;

public record OpenSessionCreateRequest(
        UUID instanceId,
        String agentId,
        String externalUserId,
        String externalSessionKey,
        String title,
        Map<String, Object> metadata
) {
}
