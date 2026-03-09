package com.fun.ai.claw.api.model;

public record UpsertInstanceConfigRequest(
        String configToml,
        String updatedBy
) {
}
