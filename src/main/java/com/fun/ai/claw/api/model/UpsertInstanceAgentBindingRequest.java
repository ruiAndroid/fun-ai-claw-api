package com.fun.ai.claw.api.model;

import java.util.List;

public record UpsertInstanceAgentBindingRequest(
        String provider,
        String model,
        Double temperature,
        Boolean agentic,
        String systemPrompt,
        List<String> allowedTools,
        String updatedBy
) {
}
