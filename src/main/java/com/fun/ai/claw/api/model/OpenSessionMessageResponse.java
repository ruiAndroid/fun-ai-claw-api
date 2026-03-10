package com.fun.ai.claw.api.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record OpenSessionMessageResponse(
        UUID id,
        UUID sessionId,
        String eventType,
        String role,
        String content,
        String thinkingContent,
        Map<String, Object> interaction,
        String providerMessageId,
        Long providerSequence,
        boolean pending,
        Instant emittedAt,
        Instant createdAt,
        String rawPayload
) {
}
