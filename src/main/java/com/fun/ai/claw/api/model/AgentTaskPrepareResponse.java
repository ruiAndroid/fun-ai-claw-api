package com.fun.ai.claw.api.model;

import java.time.Instant;
import java.util.UUID;

public record AgentTaskPrepareResponse(
        UUID taskId,
        String confirmToken,
        String summary,
        Instant expiresAt
) {
}

