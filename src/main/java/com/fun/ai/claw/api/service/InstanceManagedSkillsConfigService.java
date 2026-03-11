package com.fun.ai.claw.api.service;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class InstanceManagedSkillsConfigService {
    private static final String MANAGED_SKILLS_DIR = "/workspace/skills";
    private static final Pattern SKILLS_SECTION_PATTERN = Pattern.compile(
            "(?ms)^\\s*\\[\\s*skills\\s*]\\s*(.*?)(?=^\\s*\\[[^\\]]+\\]|\\z)"
    );
    private static final Pattern OPEN_SKILLS_ENABLED_PATTERN = Pattern.compile(
            "(?m)^\\s*open_skills_enabled\\s*=.*$"
    );
    private static final Pattern OPEN_SKILLS_DIR_PATTERN = Pattern.compile(
            "(?m)^\\s*open_skills_dir\\s*=.*$"
    );

    public String applyPolicy(String configToml) {
        String normalized = normalize(configToml);
        Matcher matcher = SKILLS_SECTION_PATTERN.matcher(normalized);
        if (!matcher.find()) {
            return appendSkillsSection(normalized);
        }

        String updatedBody = replaceOrAppendProperty(
                matcher.group(1),
                OPEN_SKILLS_ENABLED_PATTERN,
                "open_skills_enabled = true"
        );
        updatedBody = replaceOrAppendProperty(
                updatedBody,
                OPEN_SKILLS_DIR_PATTERN,
                "open_skills_dir = \"" + MANAGED_SKILLS_DIR + "\""
        );

        String replacement = "[skills]\n" + ensureSectionBody(updatedBody);
        return ensureTrailingNewline(normalized.substring(0, matcher.start()) + replacement + normalized.substring(matcher.end()));
    }

    public String managedSkillsDir() {
        return MANAGED_SKILLS_DIR;
    }

    private String appendSkillsSection(String configToml) {
        StringBuilder builder = new StringBuilder(configToml);
        if (builder.length() > 0 && builder.charAt(builder.length() - 1) != '\n') {
            builder.append('\n');
        }
        if (builder.length() > 0) {
            builder.append('\n');
        }
        builder.append("[skills]\n");
        builder.append("open_skills_enabled = true\n");
        builder.append("open_skills_dir = \"").append(MANAGED_SKILLS_DIR).append("\"\n");
        return ensureTrailingNewline(builder.toString());
    }

    private String replaceOrAppendProperty(String sectionBody, Pattern pattern, String replacementLine) {
        String normalized = sectionBody == null ? "" : sectionBody.replace("\r\n", "\n");
        Matcher matcher = pattern.matcher(normalized);
        if (matcher.find()) {
            return matcher.replaceAll(Matcher.quoteReplacement(replacementLine));
        }
        String trimmed = normalized.stripTrailing();
        if (!StringUtils.hasText(trimmed)) {
            return replacementLine + "\n";
        }
        return trimmed + "\n" + replacementLine + "\n";
    }

    private String ensureSectionBody(String sectionBody) {
        String normalized = sectionBody == null ? "" : sectionBody.replace("\r\n", "\n").stripTrailing();
        if (!StringUtils.hasText(normalized)) {
            return "";
        }
        return normalized + "\n";
    }

    private String normalize(String value) {
        String normalized = value == null ? "" : value.replace("\r\n", "\n").trim();
        if (normalized.isEmpty()) {
            return "";
        }
        return normalized + "\n";
    }

    private String ensureTrailingNewline(String value) {
        if (value.isEmpty() || value.endsWith("\n")) {
            return value;
        }
        return value + "\n";
    }
}
