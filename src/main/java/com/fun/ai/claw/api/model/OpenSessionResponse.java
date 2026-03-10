package com.fun.ai.claw.api.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record OpenSessionResponse(
        UUID sessionId,
        String appId,
        UUID instanceId,
        String agentId,
        String externalUserId,
        String externalSessionKey,
        String title,
        OpenSessionStatus status,
        Map<String, Object> metadata,
        String websocketPath,
        String websocketToken,
        Instant websocketTokenExpiresAt,
        Instant createdAt,
        Instant updatedAt,
        Instant lastMessageAt
) {
}
