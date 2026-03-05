package com.fun.ai.claw.api.model;

import java.util.List;

public record AgentDescriptorResponse(
        String id,
        String provider,
        String model,
        Boolean agentic,
        List<String> allowedTools,
        String systemPrompt,
        String configPath
) {
}
