package com.fun.ai.claw.api.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

public record MgcNovelToScriptPrepareRequest(
        @NotNull UUID instanceId,
        @NotBlank String scriptContent,
        @NotBlank
        @Pattern(regexp = "小说转剧本|一句话剧本", message = "scriptType must be 小说转剧本 or 一句话剧本")
        String scriptType,
        @NotBlank String targetAudience,
        @NotNull @Min(1) @Max(1000) Integer expectedEpisodeCount
) {
}

