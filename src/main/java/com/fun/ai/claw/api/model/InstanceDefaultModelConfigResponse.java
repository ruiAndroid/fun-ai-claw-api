package com.fun.ai.claw.api.model;

import java.time.Instant;
import java.util.UUID;

public record InstanceDefaultModelConfigResponse(
        UUID instanceId,
        String runtimeConfigPath,
        String source,
        boolean overwriteOnStart,
        boolean overrideExists,
        String apiKey,
        String defaultProvider,
        String defaultModel,
        double defaultTemperature,
        Instant overrideUpdatedAt,
        String overrideUpdatedBy
) {
}
