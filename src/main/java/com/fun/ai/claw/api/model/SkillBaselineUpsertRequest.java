package com.fun.ai.claw.api.model;

public record SkillBaselineUpsertRequest(
        String skillKey,
        String displayName,
        String description,
        String sourceType,
        String sourceRef,
        Boolean enabled,
        String updatedBy
) {
}
