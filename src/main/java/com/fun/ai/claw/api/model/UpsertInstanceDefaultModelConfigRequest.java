package com.fun.ai.claw.api.model;

public record UpsertInstanceDefaultModelConfigRequest(
        String apiKey,
        String defaultProvider,
        String defaultModel,
        Double defaultTemperature,
        String updatedBy
) {
}
