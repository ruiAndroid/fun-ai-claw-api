package com.fun.ai.claw.api.model;

import jakarta.validation.constraints.NotBlank;

public record AgentTaskConfirmRequest(
        @NotBlank String confirmToken
) {
}

