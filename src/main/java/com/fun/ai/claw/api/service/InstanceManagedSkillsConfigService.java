package com.fun.ai.claw.api.service;

import com.fun.ai.claw.api.repository.InstanceSkillBindingRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class InstanceManagedSkillsConfigService {
    private static final String MANAGED_OPEN_SKILLS_DIR = "/workspace/open-skills";
    private static final Pattern SKILLS_SECTION_PATTERN = Pattern.compile(
            "(?ms)^\\s*\\[\\s*skills\\s*]\\s*(.*?)(?=^\\s*\\[[^\\]]+\\]|\\z)"
    );
    private static final Pattern OPEN_SKILLS_ENABLED_PATTERN = Pattern.compile(
            "(?m)^\\s*open_skills_enabled\\s*=.*$"
    );
    private static final Pattern OPEN_SKILLS_DIR_PATTERN = Pattern.compile(
            "(?m)^\\s*open_skills_dir\\s*=.*$"
    );
    private static final Pattern MANAGED_SKILL_BINDINGS_COMMENT_PATTERN = Pattern.compile(
            "(?m)^\\s*#\\s*managed_skill_bindings\\s*=.*$"
    );

    private final InstanceSkillBindingRepository instanceSkillBindingRepository;

    public InstanceManagedSkillsConfigService(InstanceSkillBindingRepository instanceSkillBindingRepository) {
        this.instanceSkillBindingRepository = instanceSkillBindingRepository;
    }

    public String applyPolicy(java.util.UUID instanceId, String configToml) {
        String normalized = normalize(configToml);
        String managedBindingsComment = buildManagedBindingsComment(instanceId);
        Matcher matcher = SKILLS_SECTION_PATTERN.matcher(normalized);
        if (!matcher.find()) {
            return appendSkillsSection(normalized, managedBindingsComment);
        }

        String updatedBody = replaceOrAppendProperty(
                matcher.group(1),
                OPEN_SKILLS_ENABLED_PATTERN,
                "open_skills_enabled = true"
        );
        updatedBody = replaceOrAppendProperty(
                updatedBody,
                OPEN_SKILLS_DIR_PATTERN,
                "open_skills_dir = \"" + MANAGED_OPEN_SKILLS_DIR + "\""
        );
        updatedBody = replaceOrAppendProperty(
                updatedBody,
                MANAGED_SKILL_BINDINGS_COMMENT_PATTERN,
                managedBindingsComment
        );

        String replacement = "[skills]\n" + ensureSectionBody(updatedBody);
        return ensureTrailingNewline(normalized.substring(0, matcher.start()) + replacement + normalized.substring(matcher.end()));
    }

    public String managedSkillsDir() {
        return MANAGED_OPEN_SKILLS_DIR;
    }

    private String appendSkillsSection(String configToml, String managedBindingsComment) {
        StringBuilder builder = new StringBuilder(configToml);
        if (builder.length() > 0 && builder.charAt(builder.length() - 1) != '\n') {
            builder.append('\n');
        }
        if (builder.length() > 0) {
            builder.append('\n');
        }
        builder.append("[skills]\n");
        builder.append("open_skills_enabled = true\n");
        builder.append("open_skills_dir = \"").append(MANAGED_OPEN_SKILLS_DIR).append("\"\n");
        builder.append(managedBindingsComment).append('\n');
        return ensureTrailingNewline(builder.toString());
    }

    private String buildManagedBindingsComment(java.util.UUID instanceId) {
        List<String> bindings = instanceSkillBindingRepository.findByInstanceId(instanceId).stream()
                .map(record -> record.skillKey())
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
        String rendered = bindings.stream()
                .map(this::quoteTomlString)
                .collect(Collectors.joining(", "));
        return "# managed_skill_bindings = [" + rendered + "]";
    }

    private String quoteTomlString(String value) {
        return "\"" + escapeTomlString(value) + "\"";
    }

    private String escapeTomlString(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            switch (ch) {
                case '\\' -> builder.append("\\\\");
                case '"' -> builder.append("\\\"");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> builder.append(ch);
            }
        }
        return builder.toString();
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
