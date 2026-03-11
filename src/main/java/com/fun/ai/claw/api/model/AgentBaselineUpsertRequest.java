package com.fun.ai.claw.api.model;

import java.util.List;

public record AgentBaselineUpsertRequest(
        String agentKey,
        String displayName,
        String description,
        String runtime,
        String sourceType,
        String sourceRef,
        Boolean enabled,
        String manifestJson,
        String provider,
        String model,
        Double temperature,
        Boolean agentic,
        String entrySkill,
        List<String> allowedTools,
        List<String> skillIds,
        String systemPrompt,
        String updatedBy
) {
}
