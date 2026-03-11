package com.fun.ai.claw.api.model;

import java.time.Instant;

public record AgentBaselineRecord(
        String agentKey,
        String displayName,
        String description,
        String runtime,
        String sourceType,
        String sourceRef,
        boolean enabled,
        String provider,
        String model,
        Double temperature,
        Boolean agentic,
        String allowedToolsJson,
        String systemPrompt,
        String updatedBy,
        Instant createdAt,
        Instant updatedAt
) {
}
