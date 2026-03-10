package com.fun.ai.claw.api.model;

import java.util.UUID;

public record OpenClientAppCreateRequest(
        String name,
        UUID defaultInstanceId,
        String defaultAgentId
) {
}
