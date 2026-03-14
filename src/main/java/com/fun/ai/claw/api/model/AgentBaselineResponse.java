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
        String provider,
        String model,
        Double temperature,
        Boolean agentic,
        String toolPresetKey,
        List<String> allowedToolsExtra,
        List<String> deniedTools,
        List<String> allowedTools,
        String systemPrompt,
        String updatedBy,
        Instant createdAt,
        Instant updatedAt
) {
}
