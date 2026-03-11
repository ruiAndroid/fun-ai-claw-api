package com.fun.ai.claw.api.model;

import java.time.Instant;
import java.util.UUID;

public record InstanceSkillBindingResponse(
        UUID instanceId,
        String skillKey,
        String displayName,
        String description,
        boolean baselineEnabled,
        String updatedBy,
        Instant createdAt,
        Instant updatedAt
) {
}
