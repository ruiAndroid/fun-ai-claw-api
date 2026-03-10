package com.fun.ai.claw.api.model;

import java.util.UUID;

public record OpenClientAppUpdateRequest(
        String name,
        UUID defaultInstanceId,
        String defaultAgentId,
        Boolean enabled
) {
}
