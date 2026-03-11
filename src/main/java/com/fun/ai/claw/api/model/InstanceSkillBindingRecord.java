package com.fun.ai.claw.api.model;

import java.time.Instant;
import java.util.UUID;

public record InstanceSkillBindingRecord(
        UUID instanceId,
        String skillKey,
        String updatedBy,
        Instant createdAt,
        Instant updatedAt
) {
}
