package com.fun.ai.claw.api.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record QueryClassificationRuleConfigItem(
        String hint,
        List<String> keywords,
        @JsonProperty("patterns")
        @JsonAlias("literals")
        List<String> patterns,
        Integer priority,
        Integer minLength,
        Integer maxLength
) {
}
