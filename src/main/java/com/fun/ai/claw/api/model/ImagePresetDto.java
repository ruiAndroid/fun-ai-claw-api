package com.fun.ai.claw.api.model;

public record ImagePresetDto(
        String id,
        String name,
        String image,
        InstanceRuntime runtime,
        String description,
        boolean recommended
) {
}

