package com.fun.ai.claw.api.model;

import jakarta.validation.constraints.NotBlank;

public record CreateInstanceRequest(
        @NotBlank String name,
        @NotBlank String hostId,
        @NotBlank String image,
        InstanceDesiredState desiredState
) {
}

