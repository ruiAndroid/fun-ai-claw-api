package com.fun.ai.claw.api.model;

import java.time.Instant;

public record SkillBaselineRecord(
        String skillKey,
        String displayName,
        String description,
        String sourceType,
        String sourceRef,
        boolean enabled,
        String updatedBy,
        Instant createdAt,
        Instant updatedAt
) {
}
