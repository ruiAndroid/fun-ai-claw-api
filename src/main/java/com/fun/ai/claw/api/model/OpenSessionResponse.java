package com.fun.ai.claw.api.model;

import java.time.Instant;
import java.util.UUID;

public record OpenSessionResponse(
        UUID sessionId,
        String appId,
        UUID instanceId,
        String agentId,
        String externalUserId,
        String externalSessionKey,
        OpenSessionStatus status,
        String websocketPath,
        String websocketToken,
        Instant websocketTokenExpiresAt,
        Instant createdAt,
        Instant updatedAt,
        Instant lastMessageAt
) {
}
