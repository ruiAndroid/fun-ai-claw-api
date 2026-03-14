package com.fun.ai.claw.api.model;

import java.util.List;

public record AgentToolCatalogResponse(
        List<AgentToolDefinitionResponse> tools,
        List<AgentToolPresetResponse> presets
) {
}
