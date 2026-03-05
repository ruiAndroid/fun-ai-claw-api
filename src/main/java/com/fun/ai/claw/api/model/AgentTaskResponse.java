package com.fun.ai.claw.api.model;

import java.time.Instant;
import java.util.UUID;

public record AgentTaskResponse(
        UUID taskId,
        String agentId,
        String status,
        String responseBody,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt,
        Instant finishedAt
) {
}

