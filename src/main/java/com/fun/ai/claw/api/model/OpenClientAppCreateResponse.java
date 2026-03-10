package com.fun.ai.claw.api.model;

import java.time.Instant;
import java.util.UUID;

public record OpenClientAppCreateResponse(
        String appId,
        String name,
        boolean enabled,
        UUID defaultInstanceId,
        String defaultAgentId,
        String plainSecret,
        Instant createdAt,
        Instant updatedAt
) {

    public static OpenClientAppCreateResponse from(OpenClientAppRecord record, String plainSecret) {
        return new OpenClientAppCreateResponse(
                record.appId(),
                record.name(),
                record.enabled(),
                record.defaultInstanceId(),
                record.defaultAgentId(),
                plainSecret,
                record.createdAt(),
                record.updatedAt()
        );
    }
}
