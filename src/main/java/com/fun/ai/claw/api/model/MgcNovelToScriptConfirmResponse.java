package com.fun.ai.claw.api.model;

import java.time.Instant;
import java.util.UUID;

public record MgcNovelToScriptConfirmResponse(
        UUID taskId,
        String status,
        Instant acceptedAt
) {
}

