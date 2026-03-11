package com.fun.ai.claw.api.model;

import java.time.Instant;

public record AgentBaselineSummaryResponse(
        String agentKey,
        String displayName,
        String runtime,
        String sourceType,
        String sourceRef,
        boolean enabled,
        String provider,
        String model,
        Double temperature,
        Boolean agentic,
        String updatedBy,
        Instant createdAt,
        Instant updatedAt
) {
}
