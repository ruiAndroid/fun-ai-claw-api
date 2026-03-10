package com.fun.ai.claw.api.model;

import java.time.Instant;
import java.util.UUID;

public record OpenSessionMessageRecord(
        UUID id,
        UUID sessionId,
        String eventType,
        String role,
        String content,
        String thinkingContent,
        String interactionJson,
        String rawPayload,
        String providerMessageId,
        Long providerSequence,
        boolean pending,
        Instant emittedAt,
        Instant createdAt
) {
}
