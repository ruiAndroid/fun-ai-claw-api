package com.fun.ai.claw.api.model;

import java.time.Instant;
import java.util.List;

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
        List<String> allowedTools,
        String updatedBy,
        Instant createdAt,
        Instant updatedAt
) {
}
