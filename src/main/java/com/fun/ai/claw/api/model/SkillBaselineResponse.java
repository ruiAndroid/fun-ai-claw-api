package com.fun.ai.claw.api.model;

import java.time.Instant;

public record SkillBaselineResponse(
        String skillKey,
        String displayName,
        String description,
        String sourceType,
        String sourceRef,
        boolean enabled,
        String skillMd,
        String updatedBy,
        Instant createdAt,
        Instant updatedAt
) {
}
