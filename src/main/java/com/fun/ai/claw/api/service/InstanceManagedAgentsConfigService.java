package com.fun.ai.claw.api.service;

import com.fun.ai.claw.api.model.InstanceAgentBindingRecord;
import com.fun.ai.claw.api.repository.InstanceAgentBindingRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class InstanceManagedAgentsConfigService {

    private static final Pattern AGENT_BLOCK_PATTERN = Pattern.compile(
            "(?ms)^\\s*\\[\\s*agents\\s*\\.\\s*(?:\"((?:\\\\.|[^\"\\\\])*)\"|'((?:\\\\.|[^'\\\\])*)'|([A-Za-z0-9_-]+))\\s*\\]\\s*(.*?)(?=^\\s*\\[[^\\]]+\\]|\\z)"
    );
    private static final Pattern HOOKS_SECTION_PATTERN = Pattern.compile("(?m)^\\s*\\[\\s*hooks\\s*]\\s*$");
    private static final Pattern PROVIDER_PATTERN = Pattern.compile("(?m)^\\s*provider\\s*=\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*$");
    private static final Pattern MODEL_PATTERN = Pattern.compile("(?m)^\\s*model\\s*=\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*$");
    private static final Pattern DEFAULT_PROVIDER_BASIC_PATTERN = Pattern.compile("(?m)^\\s*default_provider\\s*=\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*$");
    private static final Pattern DEFAULT_PROVIDER_LITERAL_PATTERN = Pattern.compile("(?m)^\\s*default_provider\\s*=\\s*'([^'\\r\\n]*)'\\s*$");
    private static final Pattern DEFAULT_MODEL_BASIC_PATTERN = Pattern.compile("(?m)^\\s*default_model\\s*=\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*$");
    private static final Pattern DEFAULT_MODEL_LITERAL_PATTERN = Pattern.compile("(?m)^\\s*default_model\\s*=\\s*'([^'\\r\\n]*)'\\s*$");
    private static final Pattern TEMPERATURE_PATTERN = Pattern.compile("(?m)^\\s*temperature\\s*=\\s*([-+]?[0-9]+(?:\\.[0-9]+)?)\\s*$");
    private static final Pattern SYSTEM_PROMPT_PATTERN = Pattern.compile("(?m)^\\s*system_prompt\\s*=\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*$");
    private static final Pattern SYSTEM_PROMPT_LITERAL_PATTERN = Pattern.compile("(?m)^\\s*system_prompt\\s*=\\s*'([^'\\r\\n]*)'\\s*$");
    private static final Pattern SYSTEM_PROMPT_MULTILINE_BASIC_PATTERN = Pattern.compile("(?ms)^\\s*system_prompt\\s*=\\s*\"\"\"(.*?)\"\"\"\\s*$");
    private static final Pattern SYSTEM_PROMPT_MULTILINE_LITERAL_PATTERN = Pattern.compile("(?ms)^\\s*system_prompt\\s*=\\s*'''(.*?)'''\\s*$");
    private static final Pattern AGENTIC_PATTERN = Pattern.compile("(?m)^\\s*agentic\\s*=\\s*(true|false)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALLOWED_TOOLS_PATTERN = Pattern.compile("(?ms)^\\s*allowed_tools\\s*=\\s*\\[(.*?)]\\s*$");
    private static final Pattern ALLOWED_SKILLS_PATTERN = Pattern.compile("(?ms)^\\s*allowed_skills\\s*=\\s*\\[(.*?)]\\s*$");
    private static final Pattern ARRAY_QUOTED_STRING_PATTERN = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"|'((?:\\\\.|[^'\\\\])*)'");

    private final InstanceAgentBindingRepository instanceAgentBindingRepository;

    public InstanceManagedAgentsConfigService(InstanceAgentBindingRepository instanceAgentBindingRepository) {
        this.instanceAgentBindingRepository = instanceAgentBindingRepository;
    }

    public String applyPolicy(UUID instanceId, String configToml) {
        List<InstanceAgentBindingRecord> bindings = instanceAgentBindingRepository.findByInstanceId(instanceId).stream()
                .sorted(Comparator.comparing(InstanceAgentBindingRecord::agentKey))
                .toList();
        return rewriteAgentBlocks(configToml, bindings, false);
    }

    public String materializeBindings(UUID instanceId, String configToml) {
        List<InstanceAgentBindingRecord> bindings = instanceAgentBindingRepository.findByInstanceId(instanceId).stream()
                .sorted(Comparator.comparing(InstanceAgentBindingRecord::agentKey))
                .toList();
        return rewriteAgentBlocks(configToml, bindings, true);
    }

    public ManagedAgentDefaults extractDefaults(String configToml) {
        return extractDefaultsFromNormalized(normalize(configToml));
    }

    private String rewriteAgentBlocks(String configToml,
                                      List<InstanceAgentBindingRecord> bindings,
                                      boolean stripBlocksWhenBindingsEmpty) {
        String normalized = normalize(configToml);
        ManagedAgentDefaults defaults = extractDefaultsFromNormalized(normalized);
        List<InstanceAgentBindingRecord> effectiveBindings = bindings.stream()
                .map(binding -> applyDefaults(binding, defaults))
                .toList();
        if (bindings.isEmpty() && !stripBlocksWhenBindingsEmpty) {
            return normalized;
        }
        List<AgentBlockRange> ranges = findAgentBlockRanges(normalized);
        if (ranges.isEmpty() && effectiveBindings.isEmpty()) {
            return normalized;
        }
        int insertionPoint = !ranges.isEmpty()
                ? ranges.get(0).start()
                : findInsertionPoint(normalized);
        String stripped = removeRanges(normalized, ranges);
        String before = stripped.substring(0, insertionPoint).stripTrailing();
        String after = stripped.substring(insertionPoint).stripLeading();
        StringBuilder builder = new StringBuilder();
        if (StringUtils.hasText(before)) {
            builder.append(before);
        }
        if (!effectiveBindings.isEmpty()) {
            if (builder.length() > 0) {
                builder.append('\n').append('\n');
            }
            builder.append(renderBindings(effectiveBindings).stripTrailing());
        }
        if (StringUtils.hasText(after)) {
            if (builder.length() > 0) {
                builder.append('\n').append('\n');
            }
            builder.append(after);
        }
        return ensureTrailingNewline(builder.toString().replace("\r\n", "\n"));
    }
    public List<InstanceAgentBindingRecord> parseBindings(UUID instanceId, String configToml) {
        String normalized = normalize(configToml);
        ManagedAgentDefaults defaults = extractDefaultsFromNormalized(normalized);
        Matcher matcher = AGENT_BLOCK_PATTERN.matcher(normalized);
        List<InstanceAgentBindingRecord> records = new ArrayList<>();
        while (matcher.find()) {
            String agentKey = unescapeTomlString(firstNonBlank(matcher.group(1), matcher.group(2), matcher.group(3))).trim();
            if (!StringUtils.hasText(agentKey)) {
                continue;
            }
            String block = matcher.group(4);
            records.add(new InstanceAgentBindingRecord(
                    instanceId,
                    agentKey,
                    firstNonBlankOrNull(findStringValue(PROVIDER_PATTERN, block), defaults.defaultProvider()),
                    firstNonBlankOrNull(findStringValue(MODEL_PATTERN, block), defaults.defaultModel()),
                    findDoubleValue(TEMPERATURE_PATTERN, block),
                    findBooleanValue(AGENTIC_PATTERN, block),
                    findSystemPromptValue(block),
                    findStringArrayValue(ALLOWED_TOOLS_PATTERN, block),
                    findStringArrayValue(ALLOWED_SKILLS_PATTERN, block),
                    stripManagedProperties(block),
                    "bootstrap",
                    null,
                    null
            ));
        }
        return records;
    }

    private String renderBindings(List<InstanceAgentBindingRecord> bindings) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < bindings.size(); index++) {
            if (index > 0) {
                builder.append('\n');
            }
            builder.append(renderBinding(bindings.get(index)));
        }
        return builder.toString().stripTrailing();
    }

    private String renderBinding(InstanceAgentBindingRecord record) {
        StringBuilder builder = new StringBuilder();
        builder.append("[agents.\"").append(escapeTomlString(record.agentKey())).append("\"]\n");
        appendQuotedProperty(builder, "provider", record.provider());
        appendQuotedProperty(builder, "model", record.model());
        appendNumericProperty(builder, "temperature", record.temperature());
        appendMultilineProperty(builder, "system_prompt", record.systemPrompt());
        appendBooleanProperty(builder, "agentic", record.agentic());
        appendStringArrayProperty(builder, "allowed_tools", record.allowedTools());
        appendStringArrayProperty(builder, "allowed_skills", record.allowedSkills());
        appendExtraConfig(builder, record.extraConfigToml());
        return builder.toString().stripTrailing() + "\n";
    }

    private void appendQuotedProperty(StringBuilder builder, String key, String value) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        builder.append(key)
                .append(" = \"")
                .append(escapeTomlString(value.trim()))
                .append("\"\n");
    }

    private void appendNumericProperty(StringBuilder builder, String key, Double value) {
        if (value == null) {
            return;
        }
        builder.append(key).append(" = ").append(value).append('\n');
    }

    private void appendBooleanProperty(StringBuilder builder, String key, Boolean value) {
        if (value == null) {
            return;
        }
        builder.append(key).append(" = ").append(Boolean.TRUE.equals(value) ? "true" : "false").append('\n');
    }

    private void appendMultilineProperty(StringBuilder builder, String key, String value) {
        if (value == null) {
            return;
        }
        String normalized = value.replace("\r\n", "\n");
        if (!normalized.contains("\n")) {
            builder.append(key)
                    .append(" = \"")
                    .append(escapeTomlString(normalized))
                    .append("\"\n");
            return;
        }
        builder.append(key).append(" = \"\"\"\n");
        builder.append(normalized);
        if (!normalized.endsWith("\n")) {
            builder.append('\n');
        }
        builder.append("\"\"\"\n");
    }

    private void appendStringArrayProperty(StringBuilder builder, String key, List<String> values) {
        List<String> normalized = normalizeStringList(values);
        builder.append(key).append(" = [");
        if (normalized.isEmpty()) {
            builder.append("]\n");
            return;
        }
        builder.append('\n');
        for (String value : normalized) {
            builder.append("    \"").append(escapeTomlString(value)).append("\",\n");
        }
        builder.append("]\n");
    }

    private void appendExtraConfig(StringBuilder builder, String extraConfigToml) {
        if (!StringUtils.hasText(extraConfigToml)) {
            return;
        }
        String normalized = extraConfigToml.replace("\r\n", "\n").strip();
        if (!normalized.isEmpty()) {
            builder.append(normalized);
            if (!normalized.endsWith("\n")) {
                builder.append('\n');
            }
        }
    }

    private String stripManagedProperties(String block) {
        String normalized = block == null ? "" : block.replace("\r\n", "\n");
        normalized = PROVIDER_PATTERN.matcher(normalized).replaceAll("");
        normalized = MODEL_PATTERN.matcher(normalized).replaceAll("");
        normalized = TEMPERATURE_PATTERN.matcher(normalized).replaceAll("");
        normalized = SYSTEM_PROMPT_PATTERN.matcher(normalized).replaceAll("");
        normalized = SYSTEM_PROMPT_LITERAL_PATTERN.matcher(normalized).replaceAll("");
        normalized = SYSTEM_PROMPT_MULTILINE_BASIC_PATTERN.matcher(normalized).replaceAll("");
        normalized = SYSTEM_PROMPT_MULTILINE_LITERAL_PATTERN.matcher(normalized).replaceAll("");
        normalized = AGENTIC_PATTERN.matcher(normalized).replaceAll("");
        normalized = ALLOWED_TOOLS_PATTERN.matcher(normalized).replaceAll("");
        normalized = ALLOWED_SKILLS_PATTERN.matcher(normalized).replaceAll("");
        normalized = normalized.replaceAll("(?m)^[ \\t]*\\r?\\n", "");
        String trimmed = normalized.strip();
        return trimmed.isEmpty() ? null : trimmed + "\n";
    }

    private ManagedAgentDefaults extractDefaultsFromNormalized(String normalizedConfigToml) {
        return new ManagedAgentDefaults(
                findStringValue(normalizedConfigToml, DEFAULT_PROVIDER_BASIC_PATTERN, DEFAULT_PROVIDER_LITERAL_PATTERN),
                findStringValue(normalizedConfigToml, DEFAULT_MODEL_BASIC_PATTERN, DEFAULT_MODEL_LITERAL_PATTERN)
        );
    }

    private InstanceAgentBindingRecord applyDefaults(InstanceAgentBindingRecord record, ManagedAgentDefaults defaults) {
        String provider = firstNonBlankOrNull(record.provider(), defaults.defaultProvider());
        String model = firstNonBlankOrNull(record.model(), defaults.defaultModel());
        if (Objects.equals(provider, record.provider()) && Objects.equals(model, record.model())) {
            return record;
        }
        return new InstanceAgentBindingRecord(
                record.instanceId(),
                record.agentKey(),
                provider,
                model,
                record.temperature(),
                record.agentic(),
                record.systemPrompt(),
                record.allowedTools(),
                record.allowedSkills(),
                record.extraConfigToml(),
                record.updatedBy(),
                record.createdAt(),
                record.updatedAt()
        );
    }

    private List<AgentBlockRange> findAgentBlockRanges(String configToml) {
        Matcher matcher = AGENT_BLOCK_PATTERN.matcher(configToml);
        List<AgentBlockRange> ranges = new ArrayList<>();
        while (matcher.find()) {
            ranges.add(new AgentBlockRange(matcher.start(), matcher.end()));
        }
        return ranges;
    }

    private int findInsertionPoint(String configToml) {
        Matcher hooksMatcher = HOOKS_SECTION_PATTERN.matcher(configToml);
        if (hooksMatcher.find()) {
            return hooksMatcher.start();
        }
        return configToml.length();
    }

    private String removeRanges(String configToml, List<AgentBlockRange> ranges) {
        if (ranges.isEmpty()) {
            return configToml;
        }
        StringBuilder builder = new StringBuilder();
        int cursor = 0;
        for (AgentBlockRange range : ranges) {
            builder.append(configToml, cursor, range.start());
            cursor = range.end();
        }
        builder.append(configToml.substring(cursor));
        return builder.toString();
    }

    private String findStringValue(Pattern pattern, String block) {
        Matcher matcher = pattern.matcher(block);
        if (!matcher.find()) {
            return null;
        }
        return unescapeTomlString(matcher.group(1)).trim();
    }

    private String findStringValue(String content, Pattern basicPattern, Pattern literalPattern) {
        Matcher basicMatcher = basicPattern.matcher(content);
        if (basicMatcher.find()) {
            return trimToNull(unescapeTomlString(basicMatcher.group(1)));
        }
        Matcher literalMatcher = literalPattern.matcher(content);
        if (literalMatcher.find()) {
            return trimToNull(literalMatcher.group(1));
        }
        return null;
    }

    private Double findDoubleValue(Pattern pattern, String block) {
        Matcher matcher = pattern.matcher(block);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Double.parseDouble(matcher.group(1).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Boolean findBooleanValue(Pattern pattern, String block) {
        Matcher matcher = pattern.matcher(block);
        if (!matcher.find()) {
            return null;
        }
        return Boolean.parseBoolean(matcher.group(1).trim());
    }

    private List<String> findStringArrayValue(Pattern pattern, String block) {
        Matcher matcher = pattern.matcher(block);
        if (!matcher.find()) {
            return List.of();
        }
        Matcher valueMatcher = ARRAY_QUOTED_STRING_PATTERN.matcher(matcher.group(1));
        LinkedHashSet<String> values = new LinkedHashSet<>();
        while (valueMatcher.find()) {
            String raw = StringUtils.hasText(valueMatcher.group(1)) ? valueMatcher.group(1) : valueMatcher.group(2);
            String value = unescapeTomlString(raw).trim();
            if (StringUtils.hasText(value)) {
                values.add(value);
            }
        }
        return List.copyOf(values);
    }

    private String findSystemPromptValue(String block) {
        String basicValue = findStringValue(SYSTEM_PROMPT_PATTERN, block);
        if (basicValue != null) {
            return basicValue;
        }
        Matcher literalMatcher = SYSTEM_PROMPT_LITERAL_PATTERN.matcher(block);
        if (literalMatcher.find()) {
            return literalMatcher.group(1);
        }
        Matcher multilineBasicMatcher = SYSTEM_PROMPT_MULTILINE_BASIC_PATTERN.matcher(block);
        if (multilineBasicMatcher.find()) {
            return unescapeTomlString(normalizeTomlMultilineBody(multilineBasicMatcher.group(1)));
        }
        Matcher multilineLiteralMatcher = SYSTEM_PROMPT_MULTILINE_LITERAL_PATTERN.matcher(block);
        if (multilineLiteralMatcher.find()) {
            return normalizeTomlMultilineBody(multilineLiteralMatcher.group(1));
        }
        return null;
    }

    private List<String> normalizeStringList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                normalized.add(value.trim());
            }
        }
        return List.copyOf(normalized);
    }

    private String normalizeTomlMultilineBody(String value) {
        if (value == null) {
            return "";
        }
        if (value.startsWith("\r\n")) {
            return value.substring(2);
        }
        if (value.startsWith("\n")) {
            return value.substring(1);
        }
        return value;
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

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return "";
    }

    private String firstNonBlankOrNull(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String normalized = trimToNull(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
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

    private String unescapeTomlString(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch != '\\' || index + 1 >= value.length()) {
                builder.append(ch);
                continue;
            }
            char next = value.charAt(++index);
            switch (next) {
                case 'n' -> builder.append('\n');
                case 'r' -> builder.append('\r');
                case 't' -> builder.append('\t');
                case '"' -> builder.append('"');
                case '\\' -> builder.append('\\');
                default -> builder.append(next);
            }
        }
        return builder.toString();
    }

    private record AgentBlockRange(int start, int end) {
    }

    public record ManagedAgentDefaults(
            String defaultProvider,
            String defaultModel
    ) {
    }
}
