package com.fun.ai.claw.api.model;

public record ManagedSkillAssetPayload(
        String skillKey,
        String sourceType,
        String sourceRef
) {
}
