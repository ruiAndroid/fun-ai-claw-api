package com.fun.ai.claw.api.model;

import java.util.List;

public record QueryClassificationRuleConfigItem(
        String hint,
        List<String> keywords,
        List<String> literals,
        Integer priority,
        Integer minLength,
        Integer maxLength
) {
}
