package com.fun.ai.claw.api.model;

import java.time.Instant;

public record SkillBaselineSummaryResponse(
        String skillKey,
        String displayName,
        String description,
        String sourceType,
        String sourceRef,
        boolean enabled,
        int lineCount,
        String updatedBy,
        Instant createdAt,
        Instant updatedAt
) {
}
