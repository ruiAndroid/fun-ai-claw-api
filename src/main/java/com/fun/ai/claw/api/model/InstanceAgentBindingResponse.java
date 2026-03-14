package com.fun.ai.claw.api.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record InstanceAgentBindingResponse(
        UUID instanceId,
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
        String systemPrompt,
        List<String> allowedTools,
        List<String> allowedSkills,
        String updatedBy,
        Instant createdAt,
        Instant updatedAt
) {
}
