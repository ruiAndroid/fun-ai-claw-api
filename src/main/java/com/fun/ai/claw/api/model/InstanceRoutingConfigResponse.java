package com.fun.ai.claw.api.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record InstanceRoutingConfigResponse(
        UUID instanceId,
        String runtimeConfigPath,
        String source,
        boolean overwriteOnStart,
        boolean overrideExists,
        boolean queryClassificationEnabled,
        List<ModelRouteConfigItem> modelRoutes,
        List<QueryClassificationRuleConfigItem> queryClassificationRules,
        Instant overrideUpdatedAt,
        String overrideUpdatedBy
) {
}
