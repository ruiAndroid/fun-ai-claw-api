package com.fun.ai.claw.api.model;

import java.time.Instant;
import java.util.UUID;

public record OpenSessionRecord(
        UUID id,
        String appId,
        UUID instanceId,
        String agentId,
        String externalUserId,
        String externalSessionKey,
        OpenSessionStatus status,
        String wsTokenHash,
        Instant wsTokenExpiresAt,
        Instant createdAt,
        Instant updatedAt,
        Instant lastMessageAt
) {
}
