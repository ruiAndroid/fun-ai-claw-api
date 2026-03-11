package com.fun.ai.claw.api.model;

import java.time.Instant;
import java.util.List;

public record AgentBaselineResponse(
        String agentKey,
        String displayName,
        String description,
        String runtime,
        String sourceType,
        String sourceRef,
        boolean enabled,
        String manifestJson,
        String mainAgentsMd,
        String provider,
        String model,
        Double temperature,
        Boolean agentic,
        String entrySkill,
        List<String> allowedTools,
        List<String> skillIds,
        String systemPrompt,
        String updatedBy,
        Instant createdAt,
        Instant updatedAt
) {
}
