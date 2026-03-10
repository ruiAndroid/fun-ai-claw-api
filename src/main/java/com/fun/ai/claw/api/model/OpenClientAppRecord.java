package com.fun.ai.claw.api.model;

import java.time.Instant;
import java.util.UUID;

public record OpenClientAppRecord(
        String appId,
        String name,
        String appSecret,
        boolean enabled,
        UUID defaultInstanceId,
        String defaultAgentId,
        Instant createdAt,
        Instant updatedAt
) {
}
