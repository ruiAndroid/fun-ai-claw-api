package com.fun.ai.claw.api.model;

public record UpsertAgentSystemPromptRequest(
        String systemPrompt,
        String updatedBy
) {
}
