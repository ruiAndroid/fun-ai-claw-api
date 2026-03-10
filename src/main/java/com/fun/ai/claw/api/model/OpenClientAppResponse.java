package com.fun.ai.claw.api.model;

import java.time.Instant;
import java.util.UUID;

public record OpenClientAppResponse(
        String appId,
        String name,
        boolean enabled,
        UUID defaultInstanceId,
        String defaultAgentId,
        Instant createdAt,
        Instant updatedAt
) {

    public static OpenClientAppResponse from(OpenClientAppRecord record) {
        return new OpenClientAppResponse(
                record.appId(),
                record.name(),
                record.enabled(),
                record.defaultInstanceId(),
                record.defaultAgentId(),
                record.createdAt(),
                record.updatedAt()
        );
    }
}
