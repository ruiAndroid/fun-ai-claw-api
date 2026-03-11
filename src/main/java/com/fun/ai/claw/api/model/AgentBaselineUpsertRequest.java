package com.fun.ai.claw.api.model;

public record AgentBaselineUpsertRequest(
        String agentKey,
        String displayName,
        String description,
        String runtime,
        String sourceType,
        String sourceRef,
        Boolean enabled,
        String provider,
        String model,
        Double temperature,
        Boolean agentic,
        String systemPrompt,
        String updatedBy
) {
}
