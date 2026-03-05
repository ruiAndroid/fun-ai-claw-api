package com.fun.ai.claw.api.model;

public record AgentDescriptorResponse(
        String id,
        String provider,
        String model,
        Boolean agentic
) {
}
