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
        String toolPresetKey,
        List<String> allowedToolsExtra,
        List<String> deniedTools,
        List<String> allowedTools,
        List<String> allowedSkills,
        String updatedBy,
        Instant createdAt,
        Instant updatedAt
) {
}
