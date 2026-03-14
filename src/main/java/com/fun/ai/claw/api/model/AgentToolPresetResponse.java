package com.fun.ai.claw.api.model;

import java.util.List;

public record AgentToolPresetResponse(
        String key,
        String displayName,
        String description,
        List<String> tools
) {
}
