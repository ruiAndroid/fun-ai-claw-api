package com.fun.ai.claw.api.model;

import java.time.Instant;
import java.util.UUID;

public record PairingCodeResponse(
        UUID instanceId,
        String pairingCode,
        String sourceLine,
        String note,
        Instant fetchedAt
) {
}

