package com.fun.ai.claw.api.model;

import jakarta.validation.constraints.NotNull;

public record InstanceActionRequest(
        @NotNull InstanceActionType action,
        String reason
) {
}

