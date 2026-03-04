package com.fun.ai.claw.api.model;

import java.time.Instant;
import java.util.UUID;

public record MgcNovelToScriptTaskResponse(
        UUID taskId,
        String status,
        String responseBody,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt,
        Instant finishedAt
) {
}

