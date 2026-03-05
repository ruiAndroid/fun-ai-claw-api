package com.fun.ai.claw.api.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record AgentTaskPrepareRequest(
        @NotNull UUID instanceId,
        @NotBlank String agentId,
        @NotBlank String message
) {
}

