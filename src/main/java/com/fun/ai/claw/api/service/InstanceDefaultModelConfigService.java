package com.fun.ai.claw.api.service;

import com.fun.ai.claw.api.model.InstanceConfigResponse;
import com.fun.ai.claw.api.model.InstanceDefaultModelConfigResponse;
import com.fun.ai.claw.api.model.UpsertInstanceConfigRequest;
import com.fun.ai.claw.api.model.UpsertInstanceDefaultModelConfigRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class InstanceDefaultModelConfigService {
    private static final double DEFAULT_TEMPERATURE_FALLBACK = 0.7D;

    private static final Pattern TABLE_HEADER_PATTERN = Pattern.compile("(?m)^\\s*\\[\\[?.+?]]\\s*$");
    private static final Pattern API_KEY_LINE_PATTERN = Pattern.compile("(?m)^\\s*api_key\\s*=.*$");
    private static final Pattern DEFAULT_PROVIDER_LINE_PATTERN = Pattern.compile("(?m)^\\s*default_provider\\s*=.*$");
    private static final Pattern DEFAULT_MODEL_LINE_PATTERN = Pattern.compile("(?m)^\\s*default_model\\s*=.*$");
    private static final Pattern DEFAULT_TEMPERATURE_LINE_PATTERN = Pattern.compile("(?m)^\\s*default_temperature\\s*=.*$");
    private static final Pattern API_KEY_BASIC_PATTERN = Pattern.compile("(?m)^\\s*api_key\\s*=\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*$");
    private static final Pattern API_KEY_LITERAL_PATTERN = Pattern.compile("(?m)^\\s*api_key\\s*=\\s*'([^'\\r\\n]*)'\\s*$");
    private static final Pattern DEFAULT_PROVIDER_BASIC_PATTERN = Pattern.compile("(?m)^\\s*default_provider\\s*=\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*$");
    private static final Pattern DEFAULT_PROVIDER_LITERAL_PATTERN = Pattern.compile("(?m)^\\s*default_provider\\s*=\\s*'([^'\\r\\n]*)'\\s*$");
    private static final Pattern DEFAULT_MODEL_BASIC_PATTERN = Pattern.compile("(?m)^\\s*default_model\\s*=\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*$");
    private static final Pattern DEFAULT_MODEL_LITERAL_PATTERN = Pattern.compile("(?m)^\\s*default_model\\s*=\\s*'([^'\\r\\n]*)'\\s*$");
    private static final Pattern DEFAULT_TEMPERATURE_PATTERN = Pattern.compile("(?m)^\\s*default_temperature\\s*=\\s*([-+]?[0-9]+(?:\\.[0-9]+)?)\\s*$");

    private final InstanceConfigService instanceConfigService;
    private final InstanceConfigMutationService instanceConfigMutationService;

    public InstanceDefaultModelConfigService(InstanceConfigService instanceConfigService,
                                             InstanceConfigMutationService instanceConfigMutationService) {
        this.instanceConfigService = instanceConfigService;
        this.instanceConfigMutationService = instanceConfigMutationService;
    }

    public InstanceDefaultModelConfigResponse get(UUID instanceId) {
        InstanceConfigResponse config = instanceConfigService.get(instanceId);
        ParsedDefaultModelConfig parsed = parseConfig(config.configToml());
        return buildResponse(config, parsed);
    }

    public InstanceDefaultModelConfigResponse upsert(UUID instanceId,
                                                     UpsertInstanceDefaultModelConfigRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
        }

        String defaultProvider = normalizeRequiredString(request.defaultProvider(), "defaultProvider");
        String defaultModel = normalizeRequiredString(request.defaultModel(), "defaultModel");
        double defaultTemperature = normalizeRequiredTemperature(request.defaultTemperature());
        String apiKey = normalizeOptionalString(request.apiKey());

        InstanceConfigResponse currentConfig = instanceConfigService.get(instanceId);
        String updatedToml = replaceDefaults(
                currentConfig.configToml(),
                apiKey,
                defaultProvider,
                defaultModel,
                defaultTemperature
        );
        InstanceConfigResponse persisted = instanceConfigMutationService.upsert(
                instanceId,
                new UpsertInstanceConfigRequest(updatedToml, request.updatedBy())
        );
        return buildResponse(persisted, parseConfig(persisted.configToml()));
    }

    private InstanceDefaultModelConfigResponse buildResponse(InstanceConfigResponse config,
                                                             ParsedDefaultModelConfig parsed) {
        return new InstanceDefaultModelConfigResponse(
                config.instanceId(),
                config.runtimeConfigPath(),
                config.source(),
                config.overwriteOnStart(),
                config.overrideExists(),
                parsed.apiKey() == null ? "" : parsed.apiKey(),
                parsed.defaultProvider() == null ? "" : parsed.defaultProvider(),
                parsed.defaultModel() == null ? "" : parsed.defaultModel(),
                parsed.defaultTemperature() == null ? DEFAULT_TEMPERATURE_FALLBACK : parsed.defaultTemperature(),
                config.overrideUpdatedAt(),
                config.overrideUpdatedBy()
        );
    }

    private ParsedDefaultModelConfig parseConfig(String configToml) {
        RootBlock rootBlock = splitRootBlock(normalizeToml(configToml));
        String root = rootBlock.content();
        return new ParsedDefaultModelConfig(
                findStringValue(root, API_KEY_BASIC_PATTERN, API_KEY_LITERAL_PATTERN),
                findStringValue(root, DEFAULT_PROVIDER_BASIC_PATTERN, DEFAULT_PROVIDER_LITERAL_PATTERN),
                findStringValue(root, DEFAULT_MODEL_BASIC_PATTERN, DEFAULT_MODEL_LITERAL_PATTERN),
                findDoubleValue(root, DEFAULT_TEMPERATURE_PATTERN)
        );
    }

    private String replaceDefaults(String configToml,
                                   String apiKey,
                                   String defaultProvider,
                                   String defaultModel,
                                   double defaultTemperature) {
        RootBlock rootBlock = splitRootBlock(normalizeToml(configToml));
        String updatedRoot = rootBlock.content();
        updatedRoot = replaceOrAppendProperty(updatedRoot, API_KEY_LINE_PATTERN, renderStringProperty("api_key", apiKey));
        updatedRoot = replaceOrAppendProperty(updatedRoot, DEFAULT_PROVIDER_LINE_PATTERN, renderStringProperty("default_provider", defaultProvider));
        updatedRoot = replaceOrAppendProperty(updatedRoot, DEFAULT_MODEL_LINE_PATTERN, renderStringProperty("default_model", defaultModel));
        updatedRoot = replaceOrAppendProperty(updatedRoot, DEFAULT_TEMPERATURE_LINE_PATTERN, renderDoubleProperty("default_temperature", defaultTemperature));
        return combineRootAndRest(updatedRoot, rootBlock.remainder());
    }

    private RootBlock splitRootBlock(String configToml) {
        Matcher matcher = TABLE_HEADER_PATTERN.matcher(configToml);
        if (!matcher.find()) {
            return new RootBlock(configToml, "");
        }
        return new RootBlock(configToml.substring(0, matcher.start()), configToml.substring(matcher.start()));
    }

    private String replaceOrAppendProperty(String content, Pattern linePattern, String replacementLine) {
        String normalized = content == null ? "" : content.replace("\r\n", "\n");
        Matcher matcher = linePattern.matcher(normalized);
        if (matcher.find()) {
            return ensureTrailingNewline(matcher.replaceAll(Matcher.quoteReplacement(replacementLine)));
        }
        String trimmed = normalized.stripTrailing();
        if (!StringUtils.hasText(trimmed)) {
            return replacementLine + "\n";
        }
        return trimmed + "\n" + replacementLine + "\n";
    }

    private String combineRootAndRest(String root, String rest) {
        String normalizedRoot = root == null ? "" : root.replace("\r\n", "\n").strip();
        String normalizedRest = rest == null ? "" : rest.replace("\r\n", "\n").strip();
        if (!StringUtils.hasText(normalizedRoot) && !StringUtils.hasText(normalizedRest)) {
            return "";
        }
        if (!StringUtils.hasText(normalizedRoot)) {
            return ensureTrailingNewline(normalizedRest);
        }
        if (!StringUtils.hasText(normalizedRest)) {
            return ensureTrailingNewline(normalizedRoot);
        }
        return ensureTrailingNewline(normalizedRoot + "\n\n" + normalizedRest);
    }

    private String findStringValue(String content, Pattern basicPattern, Pattern literalPattern) {
        Matcher basicMatcher = basicPattern.matcher(content);
        if (basicMatcher.find()) {
            return unescapeTomlString(basicMatcher.group(1)).trim();
        }
        Matcher literalMatcher = literalPattern.matcher(content);
        if (literalMatcher.find()) {
            return literalMatcher.group(1).trim();
        }
        return null;
    }

    private Double findDoubleValue(String content, Pattern pattern) {
        Matcher matcher = pattern.matcher(content);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Double.parseDouble(matcher.group(1).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String renderStringProperty(String key, String value) {
        return key + " = \"" + escapeTomlString(value) + "\"";
    }

    private String renderDoubleProperty(String key, double value) {
        return key + " = " + Double.toString(value);
    }

    private String normalizeToml(String value) {
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

    private String normalizeRequiredString(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " is required");
        }
        return value.trim();
    }

    private double normalizeRequiredTemperature(Double value) {
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "defaultTemperature is required");
        }
        if (!Double.isFinite(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "defaultTemperature must be finite");
        }
        return value;
    }

    private String normalizeOptionalString(String value) {
        if (value == null) {
            return "";
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

    private record RootBlock(
            String content,
            String remainder
    ) {
    }

    private record ParsedDefaultModelConfig(
            String apiKey,
            String defaultProvider,
            String defaultModel,
            Double defaultTemperature
    ) {
    }
}
