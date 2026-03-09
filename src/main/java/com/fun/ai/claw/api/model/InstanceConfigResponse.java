package com.fun.ai.claw.api.model;

import java.time.Instant;
import java.util.UUID;

public record InstanceConfigResponse(
        UUID instanceId,
        String runtimeConfigPath,
        String source,
        String configToml,
        boolean overwriteOnStart,
        boolean overrideExists,
        String defaultTemplatePath,
        Instant overrideUpdatedAt,
        String overrideUpdatedBy
) {
}
