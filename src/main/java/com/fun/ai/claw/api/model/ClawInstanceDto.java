package com.fun.ai.claw.api.model;

import java.time.Instant;
import java.util.UUID;

public record ClawInstanceDto(
        UUID id,
        String name,
        UUID hostId,
        String image,
        Integer gatewayHostPort,
        String gatewayUrl,
        String remoteConnectCommand,
        InstanceRuntime runtime,
        InstanceStatus status,
        InstanceDesiredState desiredState,
        Instant createdAt,
        Instant updatedAt
) {
}

