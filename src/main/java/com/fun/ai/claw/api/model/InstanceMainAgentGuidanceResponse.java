package com.fun.ai.claw.api.model;

import java.time.Instant;
import java.util.UUID;

public record InstanceMainAgentGuidanceResponse(
        UUID instanceId,
        String workspacePath,
        String source,
        String effectivePrompt,
        boolean overwriteOnStart,
        boolean overrideExists,
        Boolean overrideEnabled,
        String overridePrompt,
        String globalDefaultPath,
        Instant overrideUpdatedAt,
        String overrideUpdatedBy
) {
}
