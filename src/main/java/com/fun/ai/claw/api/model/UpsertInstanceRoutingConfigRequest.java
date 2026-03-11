package com.fun.ai.claw.api.model;

import java.util.List;

public record UpsertInstanceRoutingConfigRequest(
        Boolean queryClassificationEnabled,
        List<ModelRouteConfigItem> modelRoutes,
        List<QueryClassificationRuleConfigItem> queryClassificationRules,
        String updatedBy
) {
}
