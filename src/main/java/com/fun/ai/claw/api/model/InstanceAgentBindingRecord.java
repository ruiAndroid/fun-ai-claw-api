package com.fun.ai.claw.api.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record InstanceAgentBindingRecord(
        UUID instanceId,
        String agentKey,
        String provider,
        String model,
        Double temperature,
        Boolean agentic,
        String systemPrompt,
        List<String> allowedTools,
        List<String> allowedSkills,
        String extraConfigToml,
        String updatedBy,
        Instant createdAt,
        Instant updatedAt
) {
}
