package com.fun.ai.claw.api.model;

public record UpsertInstanceMainAgentGuidanceRequest(
        String prompt,
        Boolean enabled,
        String updatedBy
) {
}
