package com.fun.ai.claw.api.service;

import com.fun.ai.claw.api.model.InstanceConfigResponse;
import com.fun.ai.claw.api.model.InstanceRoutingConfigResponse;
import com.fun.ai.claw.api.model.ModelRouteConfigItem;
import com.fun.ai.claw.api.model.QueryClassificationRuleConfigItem;
import com.fun.ai.claw.api.model.UpsertInstanceConfigRequest;
import com.fun.ai.claw.api.model.UpsertInstanceRoutingConfigRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class InstanceRoutingConfigService {
    private static final Pattern TABLE_HEADER_PATTERN = Pattern.compile("(?m)^\\s*(\\[\\[?.+?]]?)\\s*$");
    private static final Pattern PROPERTY_PATTERN = Pattern.compile("(?m)^\\s*([A-Za-z0-9_]+)\\s*=");
    private static final Pattern EMBEDDING_ROUTES_PATTERN = Pattern.compile("(?m)^\\s*embedding_routes\\s*=.*$");
    private static final Pattern HEARTBEAT_HEADER_PATTERN = Pattern.compile("(?m)^\\s*\\[heartbeat]\\s*$");
    private static final int DEFAULT_RULE_PRIORITY = 100;
    private static final int DEFAULT_RULE_MIN_LENGTH = 1;
    private static final int DEFAULT_RULE_MAX_LENGTH = 4000;

    private final InstanceConfigService instanceConfigService;
    private final InstanceConfigMutationService instanceConfigMutationService;

    public InstanceRoutingConfigService(InstanceConfigService instanceConfigService,
                                        InstanceConfigMutationService instanceConfigMutationService) {
        this.instanceConfigService = instanceConfigService;
        this.instanceConfigMutationService = instanceConfigMutationService;
    }

    public InstanceRoutingConfigResponse get(UUID instanceId) {
        InstanceConfigResponse config = instanceConfigService.get(instanceId);
        ParsedRoutingConfig parsed = parseRoutingConfig(config.configToml());
        return buildResponse(config, parsed);
    }

    public InstanceRoutingConfigResponse upsert(UUID instanceId, UpsertInstanceRoutingConfigRequest request) {
        if (request == null || request.queryClassificationEnabled() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "queryClassificationEnabled is required");
        }

        boolean queryClassificationEnabled = request.queryClassificationEnabled();
        List<ModelRouteConfigItem> modelRoutes = normalizeModelRoutes(request.modelRoutes());
        List<QueryClassificationRuleConfigItem> rules = queryClassificationEnabled
                ? normalizeQueryClassificationRules(request.queryClassificationRules())
                : List.of();

        InstanceConfigResponse currentConfig = instanceConfigService.get(instanceId);
        String normalizedToml = normalizeToml(currentConfig.configToml());
        ParsedRoutingConfig parsedCurrent = parseRoutingConfig(normalizedToml);

        String withModelRoutes = replaceModelRoutesSection(normalizedToml, parsedCurrent.modelRoutes(), modelRoutes);
        ParsedRoutingConfig parsedAfterModelRoutes = parseRoutingConfig(withModelRoutes);
        String updatedToml = replaceQueryClassificationSection(
                withModelRoutes,
                parsedAfterModelRoutes.queryClassification(),
                queryClassificationEnabled,
                rules
        );

        InstanceConfigResponse persisted = instanceConfigMutationService.upsert(
                instanceId,
                new UpsertInstanceConfigRequest(updatedToml, request.updatedBy())
        );
        ParsedRoutingConfig parsedPersisted = parseRoutingConfig(persisted.configToml());
        return buildResponse(persisted, parsedPersisted);
    }

    private InstanceRoutingConfigResponse buildResponse(InstanceConfigResponse config,
                                                        ParsedRoutingConfig parsed) {
        return new InstanceRoutingConfigResponse(
                config.instanceId(),
                config.runtimeConfigPath(),
                config.source(),
                config.overwriteOnStart(),
                config.overrideExists(),
                parsed.queryClassification().enabled(),
                parsed.modelRoutes().stream().map(ParsedModelRouteBlock::item).toList(),
                parsed.queryClassification().rules().stream().map(ParsedQueryClassificationRuleBlock::item).toList(),
                config.overrideUpdatedAt(),
                config.overrideUpdatedBy()
        );
    }

    private String replaceModelRoutesSection(String configToml,
                                             List<ParsedModelRouteBlock> existingBlocks,
                                             List<ModelRouteConfigItem> requestedBlocks) {
        String rendered = renderModelRoutes(requestedBlocks, existingBlocks);
        if (existingBlocks.isEmpty()) {
            return insertSection(configToml, findModelRoutesInsertionPoint(configToml), rendered);
        }
        int start = existingBlocks.get(0).start();
        int end = existingBlocks.get(existingBlocks.size() - 1).end();
        return replaceSection(configToml, start, end, rendered);
    }

    private String replaceQueryClassificationSection(String configToml,
                                                     QueryClassificationSection existingSection,
                                                     boolean queryClassificationEnabled,
                                                     List<QueryClassificationRuleConfigItem> rules) {
        String rendered = renderQueryClassificationSection(queryClassificationEnabled, rules, existingSection);
        if (existingSection.region() == null) {
            return insertSection(configToml, findQueryClassificationInsertionPoint(configToml), rendered);
        }
        return replaceSection(configToml, existingSection.region().start(), existingSection.region().end(), rendered);
    }

    private String renderModelRoutes(List<ModelRouteConfigItem> routes, List<ParsedModelRouteBlock> existingBlocks) {
        if (routes.isEmpty()) {
            return "";
        }
        Map<String, Deque<ParsedModelRouteBlock>> existingByHint = indexModelRoutesByHint(existingBlocks);
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < routes.size(); index++) {
            ModelRouteConfigItem route = routes.get(index);
            ParsedModelRouteBlock existing = pollModelRoute(existingByHint, route.hint());
            builder.append("[[model_routes]]\n");
            builder.append("hint = ").append(renderTomlString(route.hint())).append('\n');
            builder.append("provider = ").append(renderTomlString(route.provider())).append('\n');
            builder.append("model = ").append(renderTomlString(route.model())).append('\n');
            appendExtras(builder, existing == null ? null : existing.extras());
            if (index + 1 < routes.size()) {
                builder.append('\n');
            }
        }
        return ensureTrailingNewline(builder.toString());
    }

    private String renderQueryClassificationSection(boolean enabled,
                                                    List<QueryClassificationRuleConfigItem> rules,
                                                    QueryClassificationSection existingSection) {
        StringBuilder builder = new StringBuilder();
        builder.append("[query_classification]\n");
        builder.append("enabled = ").append(enabled).append('\n');
        appendExtras(builder, existingSection.sectionExtras());

        if (!enabled || rules.isEmpty()) {
            return ensureTrailingNewline(builder.toString());
        }

        Map<String, Deque<ParsedQueryClassificationRuleBlock>> existingByHint =
                indexQueryRulesByHint(existingSection.rules());
        builder.append('\n');
        for (int index = 0; index < rules.size(); index++) {
            QueryClassificationRuleConfigItem rule = rules.get(index);
            ParsedQueryClassificationRuleBlock existing = pollQueryRule(existingByHint, rule.hint());
            builder.append("[[query_classification.rules]]\n");
            builder.append("hint = ").append(renderTomlString(rule.hint())).append('\n');
            builder.append("keywords = ").append(renderTomlStringArray(rule.keywords())).append('\n');
            builder.append("literals = ").append(renderTomlStringArray(rule.literals())).append('\n');
            builder.append("priority = ").append(rule.priority()).append('\n');
            builder.append("min_length = ").append(rule.minLength()).append('\n');
            builder.append("max_length = ").append(rule.maxLength()).append('\n');
            appendExtras(builder, existing == null ? null : existing.extras());
            if (index + 1 < rules.size()) {
                builder.append('\n');
            }
        }
        return ensureTrailingNewline(builder.toString());
    }

    private void appendExtras(StringBuilder builder, String extras) {
        if (!StringUtils.hasText(extras)) {
            return;
        }
        builder.append(ensureTrailingNewline(extras));
    }

    private String insertSection(String configToml, int index, String renderedSection) {
        if (!StringUtils.hasText(renderedSection)) {
            return cleanupToml(configToml);
        }
        String before = configToml.substring(0, index);
        String after = configToml.substring(index);
        StringBuilder builder = new StringBuilder();
        builder.append(before);
        if (!before.isEmpty() && before.charAt(before.length() - 1) != '\n') {
            builder.append('\n');
        }
        builder.append(ensureTrailingNewline(renderedSection));
        if (!after.isEmpty() && after.charAt(0) != '\n') {
            builder.append('\n');
        }
        builder.append(after);
        return cleanupToml(builder.toString());
    }

    private String replaceSection(String configToml, int start, int end, String renderedSection) {
        String before = configToml.substring(0, start);
        String after = configToml.substring(end);
        StringBuilder builder = new StringBuilder();
        builder.append(before);
        if (StringUtils.hasText(renderedSection)) {
            if (!before.isEmpty() && before.charAt(before.length() - 1) != '\n') {
                builder.append('\n');
            }
            builder.append(ensureTrailingNewline(renderedSection));
            if (!after.isEmpty() && after.charAt(0) != '\n') {
                builder.append('\n');
            }
        }
        builder.append(after);
        return cleanupToml(builder.toString());
    }

    private int findModelRoutesInsertionPoint(String configToml) {
        Matcher embeddingRoutesMatcher = EMBEDDING_ROUTES_PATTERN.matcher(configToml);
        if (embeddingRoutesMatcher.find()) {
            return embeddingRoutesMatcher.start();
        }
        List<HeaderRange> headers = findHeaders(configToml);
        if (!headers.isEmpty()) {
            return headers.get(0).start();
        }
        return configToml.length();
    }

    private int findQueryClassificationInsertionPoint(String configToml) {
        Matcher heartbeatMatcher = HEARTBEAT_HEADER_PATTERN.matcher(configToml);
        if (heartbeatMatcher.find()) {
            return heartbeatMatcher.start();
        }
        return configToml.length();
    }

    private ParsedRoutingConfig parseRoutingConfig(String configToml) {
        String normalizedToml = normalizeToml(configToml);
        List<HeaderRange> headers = findHeaders(normalizedToml);
        List<ParsedModelRouteBlock> modelRoutes = parseModelRouteBlocks(normalizedToml, headers);
        QueryClassificationSection queryClassification = parseQueryClassificationSection(normalizedToml, headers);
        return new ParsedRoutingConfig(modelRoutes, queryClassification);
    }

    private List<ParsedModelRouteBlock> parseModelRouteBlocks(String configToml, List<HeaderRange> headers) {
        List<ParsedModelRouteBlock> blocks = new ArrayList<>();
        for (int index = 0; index < headers.size(); index++) {
            HeaderRange header = headers.get(index);
            if (!header.array() || !"model_routes".equals(header.header())) {
                continue;
            }
            int bodyStart = skipNewline(configToml, header.end());
            int end = index + 1 < headers.size() ? headers.get(index + 1).start() : configToml.length();
            String body = configToml.substring(bodyStart, end);
            List<PropertyValue> properties = extractProperties(body);
            ModelRouteConfigItem item = new ModelRouteConfigItem(
                    safeValue(parseTomlString(findProperty(properties, "hint"))),
                    safeValue(parseTomlString(findProperty(properties, "provider"))),
                    safeValue(parseTomlString(findProperty(properties, "model")))
            );
            String extras = extractExtras(body, properties, Set.of("hint", "provider", "model"));
            blocks.add(new ParsedModelRouteBlock(item, extras, header.start(), end));
        }
        return List.copyOf(blocks);
    }

    private QueryClassificationSection parseQueryClassificationSection(String configToml, List<HeaderRange> headers) {
        SectionRange region = findQueryClassificationRegion(headers, configToml.length());
        if (region == null) {
            return new QueryClassificationSection(false, "", null, List.of());
        }

        HeaderRange tableHeader = null;
        int tableHeaderIndex = -1;
        for (int index = 0; index < headers.size(); index++) {
            HeaderRange header = headers.get(index);
            if (!header.array() && "query_classification".equals(header.header())
                    && header.start() >= region.start() && header.start() < region.end()) {
                tableHeader = header;
                tableHeaderIndex = index;
                break;
            }
        }

        String sectionExtras = "";
        Boolean enabled = null;
        if (tableHeader != null) {
            int bodyStart = skipNewline(configToml, tableHeader.end());
            int bodyEnd = tableHeaderIndex + 1 < headers.size()
                    ? Math.min(headers.get(tableHeaderIndex + 1).start(), region.end())
                    : region.end();
            String tableBody = configToml.substring(bodyStart, bodyEnd);
            List<PropertyValue> tableProperties = extractProperties(tableBody);
            enabled = parseTomlBoolean(findProperty(tableProperties, "enabled"));
            sectionExtras = extractExtras(tableBody, tableProperties, Set.of("enabled"));
        }

        List<ParsedQueryClassificationRuleBlock> rules = new ArrayList<>();
        for (int index = 0; index < headers.size(); index++) {
            HeaderRange header = headers.get(index);
            if (!header.array() || !"query_classification.rules".equals(header.header())) {
                continue;
            }
            if (header.start() < region.start() || header.start() >= region.end()) {
                continue;
            }
            int bodyStart = skipNewline(configToml, header.end());
            int end = index + 1 < headers.size()
                    ? Math.min(headers.get(index + 1).start(), region.end())
                    : region.end();
            String body = configToml.substring(bodyStart, end);
            List<PropertyValue> properties = extractProperties(body);
            QueryClassificationRuleConfigItem item = new QueryClassificationRuleConfigItem(
                    safeValue(parseTomlString(findProperty(properties, "hint"))),
                    parseTomlStringArray(findProperty(properties, "keywords")),
                    parseTomlStringArray(findProperty(properties, "literals")),
                    parseTomlInteger(findProperty(properties, "priority")),
                    parseTomlInteger(findProperty(properties, "min_length")),
                    parseTomlInteger(findProperty(properties, "max_length"))
            );
            String extras = extractExtras(body, properties, Set.of(
                    "hint",
                    "keywords",
                    "literals",
                    "priority",
                    "min_length",
                    "max_length"
            ));
            rules.add(new ParsedQueryClassificationRuleBlock(item, extras));
        }

        boolean resolvedEnabled = enabled != null ? enabled : !rules.isEmpty();
        return new QueryClassificationSection(resolvedEnabled, sectionExtras, region, List.copyOf(rules));
    }

    private SectionRange findQueryClassificationRegion(List<HeaderRange> headers, int textLength) {
        int firstRelevantIndex = -1;
        for (int index = 0; index < headers.size(); index++) {
            String header = headers.get(index).header();
            if ("query_classification".equals(header) || header.startsWith("query_classification.")) {
                firstRelevantIndex = index;
                break;
            }
        }
        if (firstRelevantIndex < 0) {
            return null;
        }
        int start = headers.get(firstRelevantIndex).start();
        int end = textLength;
        for (int index = firstRelevantIndex + 1; index < headers.size(); index++) {
            String header = headers.get(index).header();
            if (!"query_classification".equals(header) && !header.startsWith("query_classification.")) {
                end = headers.get(index).start();
                break;
            }
        }
        return new SectionRange(start, end);
    }

    private List<HeaderRange> findHeaders(String configToml) {
        Matcher matcher = TABLE_HEADER_PATTERN.matcher(configToml);
        List<HeaderRange> headers = new ArrayList<>();
        while (matcher.find()) {
            String rawHeader = matcher.group(1).trim();
            headers.add(new HeaderRange(normalizeHeader(rawHeader), rawHeader.startsWith("[["), matcher.start(), matcher.end()));
        }
        return List.copyOf(headers);
    }

    private List<PropertyValue> extractProperties(String body) {
        Matcher matcher = PROPERTY_PATTERN.matcher(body);
        List<PropertyValue> properties = new ArrayList<>();
        while (matcher.find()) {
            String name = matcher.group(1);
            int valueStart = matcher.end();
            int end = findPropertyEnd(body, valueStart);
            String rawValue = body.substring(valueStart, end).trim();
            properties.add(new PropertyValue(name, rawValue, matcher.start(), end));
        }
        return List.copyOf(properties);
    }

    private int findPropertyEnd(String body, int valueStart) {
        int index = valueStart;
        while (index < body.length() && (body.charAt(index) == ' ' || body.charAt(index) == '\t')) {
            index++;
        }
        if (index >= body.length()) {
            return body.length();
        }
        if (startsWith(body, index, "\"\"\"") || startsWith(body, index, "'''")) {
            String delimiter = body.substring(index, index + 3);
            int closing = body.indexOf(delimiter, index + 3);
            if (closing < 0) {
                return body.length();
            }
            int end = closing + 3;
            while (end < body.length() && body.charAt(end) != '\n') {
                end++;
            }
            if (end < body.length()) {
                end++;
            }
            return end;
        }
        if (body.charAt(index) == '[') {
            return findArrayEnd(body, index);
        }
        int end = index;
        while (end < body.length() && body.charAt(end) != '\n') {
            end++;
        }
        if (end < body.length()) {
            end++;
        }
        return end;
    }

    private int findArrayEnd(String body, int startIndex) {
        int depth = 0;
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean escaped = false;
        for (int index = startIndex; index < body.length(); index++) {
            char current = body.charAt(index);
            if (inDoubleQuote) {
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (current == '\\') {
                    escaped = true;
                    continue;
                }
                if (current == '"') {
                    inDoubleQuote = false;
                }
                continue;
            }
            if (inSingleQuote) {
                if (current == '\'') {
                    inSingleQuote = false;
                }
                continue;
            }
            if (current == '"') {
                inDoubleQuote = true;
                continue;
            }
            if (current == '\'') {
                inSingleQuote = true;
                continue;
            }
            if (current == '[') {
                depth++;
                continue;
            }
            if (current == ']') {
                depth--;
                if (depth == 0) {
                    int end = index + 1;
                    while (end < body.length() && body.charAt(end) != '\n') {
                        end++;
                    }
                    if (end < body.length()) {
                        end++;
                    }
                    return end;
                }
            }
        }
        return body.length();
    }

    private String findProperty(List<PropertyValue> properties, String name) {
        for (PropertyValue property : properties) {
            if (name.equals(property.name())) {
                return property.rawValue();
            }
        }
        return null;
    }

    private String extractExtras(String body, List<PropertyValue> properties, Set<String> handledProperties) {
        List<PropertyValue> matched = properties.stream()
                .filter(property -> handledProperties.contains(property.name()))
                .sorted(Comparator.comparingInt(PropertyValue::start).reversed())
                .toList();
        String result = body;
        for (PropertyValue property : matched) {
            result = result.substring(0, property.start()) + result.substring(property.end());
        }
        String normalized = result.replace("\r\n", "\n").trim();
        if (!StringUtils.hasText(normalized)) {
            return "";
        }
        return ensureTrailingNewline(compactBlankLines(normalized));
    }

    private Map<String, Deque<ParsedModelRouteBlock>> indexModelRoutesByHint(List<ParsedModelRouteBlock> blocks) {
        Map<String, Deque<ParsedModelRouteBlock>> indexed = new LinkedHashMap<>();
        for (ParsedModelRouteBlock block : blocks) {
            indexed.computeIfAbsent(block.item().hint(), ignored -> new ArrayDeque<>()).add(block);
        }
        return indexed;
    }

    private ParsedModelRouteBlock pollModelRoute(Map<String, Deque<ParsedModelRouteBlock>> indexed, String hint) {
        Deque<ParsedModelRouteBlock> queue = indexed.get(hint);
        return queue == null ? null : queue.pollFirst();
    }

    private Map<String, Deque<ParsedQueryClassificationRuleBlock>> indexQueryRulesByHint(
            List<ParsedQueryClassificationRuleBlock> blocks) {
        Map<String, Deque<ParsedQueryClassificationRuleBlock>> indexed = new LinkedHashMap<>();
        for (ParsedQueryClassificationRuleBlock block : blocks) {
            indexed.computeIfAbsent(block.item().hint(), ignored -> new ArrayDeque<>()).add(block);
        }
        return indexed;
    }

    private ParsedQueryClassificationRuleBlock pollQueryRule(
            Map<String, Deque<ParsedQueryClassificationRuleBlock>> indexed,
            String hint) {
        Deque<ParsedQueryClassificationRuleBlock> queue = indexed.get(hint);
        return queue == null ? null : queue.pollFirst();
    }

    private List<ModelRouteConfigItem> normalizeModelRoutes(List<ModelRouteConfigItem> routes) {
        if (routes == null || routes.isEmpty()) {
            return List.of();
        }
        List<ModelRouteConfigItem> normalized = new ArrayList<>();
        for (int index = 0; index < routes.size(); index++) {
            ModelRouteConfigItem route = routes.get(index);
            if (route == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "modelRoutes[" + index + "] is required");
            }
            String hint = requireText(route.hint(), "modelRoutes[" + index + "].hint");
            String provider = requireText(route.provider(), "modelRoutes[" + index + "].provider");
            String model = requireText(route.model(), "modelRoutes[" + index + "].model");
            normalized.add(new ModelRouteConfigItem(hint, provider, model));
        }
        return List.copyOf(normalized);
    }

    private List<QueryClassificationRuleConfigItem> normalizeQueryClassificationRules(
            List<QueryClassificationRuleConfigItem> rules) {
        if (rules == null || rules.isEmpty()) {
            return List.of();
        }
        List<QueryClassificationRuleConfigItem> normalized = new ArrayList<>();
        for (int index = 0; index < rules.size(); index++) {
            QueryClassificationRuleConfigItem rule = rules.get(index);
            if (rule == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "queryClassificationRules[" + index + "] is required");
            }
            String hint = requireText(rule.hint(), "queryClassificationRules[" + index + "].hint");
            List<String> keywords = normalizeStringList(rule.keywords());
            List<String> literals = normalizeStringList(rule.literals());
            int priority = rule.priority() == null ? DEFAULT_RULE_PRIORITY : rule.priority();
            int minLength = rule.minLength() == null ? DEFAULT_RULE_MIN_LENGTH : rule.minLength();
            int maxLength = rule.maxLength() == null ? DEFAULT_RULE_MAX_LENGTH : rule.maxLength();
            if (priority < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "queryClassificationRules[" + index + "].priority must be >= 0");
            }
            if (minLength < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "queryClassificationRules[" + index + "].minLength must be >= 0");
            }
            if (maxLength < minLength) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "queryClassificationRules[" + index + "].maxLength must be >= minLength");
            }
            normalized.add(new QueryClassificationRuleConfigItem(
                    hint,
                    keywords,
                    literals,
                    priority,
                    minLength,
                    maxLength
            ));
        }
        return List.copyOf(normalized);
    }

    private List<String> normalizeStringList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String value : values) {
            if (!StringUtils.hasText(value)) {
                continue;
            }
            normalized.add(value.trim());
        }
        return List.copyOf(normalized);
    }

    private String requireText(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " is required");
        }
        return value.trim();
    }

    private String safeValue(String value) {
        return value == null ? "" : value;
    }

    private Boolean parseTomlBoolean(String rawValue) {
        if (!StringUtils.hasText(rawValue)) {
            return null;
        }
        String normalized = rawValue.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(normalized)) {
            return true;
        }
        if ("false".equals(normalized)) {
            return false;
        }
        return null;
    }

    private Integer parseTomlInteger(String rawValue) {
        if (!StringUtils.hasText(rawValue)) {
            return null;
        }
        try {
            return Integer.parseInt(rawValue.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private List<String> parseTomlStringArray(String rawValue) {
        if (!StringUtils.hasText(rawValue)) {
            return List.of();
        }
        String trimmed = rawValue.trim();
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        int index = 1;
        while (index < trimmed.length() - 1) {
            char current = trimmed.charAt(index);
            if (Character.isWhitespace(current) || current == ',') {
                index++;
                continue;
            }
            if (current == '"' || current == '\'') {
                int end = findQuotedStringEnd(trimmed, index, current);
                if (end < 0) {
                    break;
                }
                String token = trimmed.substring(index, end + 1);
                values.add(safeValue(parseTomlString(token)));
                index = end + 1;
                continue;
            }
            int tokenEnd = index;
            while (tokenEnd < trimmed.length() - 1 && trimmed.charAt(tokenEnd) != ',') {
                tokenEnd++;
            }
            String token = trimmed.substring(index, tokenEnd).trim();
            if (StringUtils.hasText(token)) {
                values.add(token);
            }
            index = tokenEnd + 1;
        }
        return List.copyOf(values);
    }

    private int findQuotedStringEnd(String text, int start, char quote) {
        boolean escaped = false;
        for (int index = start + 1; index < text.length(); index++) {
            char current = text.charAt(index);
            if (quote == '"' && !escaped && current == '\\') {
                escaped = true;
                continue;
            }
            if (current == quote && (!escaped || quote == '\'')) {
                return index;
            }
            escaped = false;
        }
        return -1;
    }

    private String parseTomlString(String rawValue) {
        if (!StringUtils.hasText(rawValue)) {
            return null;
        }
        String trimmed = rawValue.trim();
        if ((trimmed.startsWith("\"\"\"") && trimmed.endsWith("\"\"\""))
                || (trimmed.startsWith("'''") && trimmed.endsWith("'''"))) {
            return trimmed.substring(3, trimmed.length() - 3);
        }
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            char quote = trimmed.charAt(0);
            String content = trimmed.substring(1, trimmed.length() - 1);
            return quote == '"' ? unescapeTomlBasicString(content) : content;
        }
        return trimmed;
    }

    private String unescapeTomlBasicString(String value) {
        StringBuilder builder = new StringBuilder();
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (!escaped && current == '\\') {
                escaped = true;
                continue;
            }
            if (escaped) {
                switch (current) {
                    case 'n' -> builder.append('\n');
                    case 't' -> builder.append('\t');
                    case 'r' -> builder.append('\r');
                    case '"' -> builder.append('"');
                    case '\\' -> builder.append('\\');
                    default -> builder.append(current);
                }
                escaped = false;
                continue;
            }
            builder.append(current);
        }
        if (escaped) {
            builder.append('\\');
        }
        return builder.toString();
    }

    private String renderTomlStringArray(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        List<String> rendered = values.stream()
                .map(this::renderTomlString)
                .toList();
        return "[" + String.join(", ", rendered) + "]";
    }

    private String renderTomlString(String value) {
        String normalized = value == null ? "" : value;
        String escaped = normalized
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
        return "\"" + escaped + "\"";
    }

    private String normalizeToml(String configToml) {
        String normalized = configToml == null ? "" : configToml.replace("\r\n", "\n");
        if (normalized.isEmpty()) {
            return "";
        }
        return ensureTrailingNewline(normalized);
    }

    private String cleanupToml(String value) {
        return ensureTrailingNewline(value.replace("\r\n", "\n"));
    }

    private String compactBlankLines(String value) {
        String normalized = value;
        while (normalized.contains("\n\n\n")) {
            normalized = normalized.replace("\n\n\n", "\n\n");
        }
        return normalized;
    }

    private String ensureTrailingNewline(String value) {
        if (value.isEmpty() || value.endsWith("\n")) {
            return value;
        }
        return value + "\n";
    }

    private String normalizeHeader(String rawHeader) {
        String trimmed = rawHeader.trim();
        if (trimmed.startsWith("[[") && trimmed.endsWith("]]")) {
            return trimmed.substring(2, trimmed.length() - 2).trim();
        }
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            return trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    private int skipNewline(String text, int index) {
        int cursor = index;
        while (cursor < text.length() && (text.charAt(cursor) == '\n' || text.charAt(cursor) == '\r')) {
            cursor++;
        }
        return cursor;
    }

    private boolean startsWith(String text, int offset, String prefix) {
        return offset >= 0
                && offset + prefix.length() <= text.length()
                && text.startsWith(prefix, offset);
    }

    private record ParsedRoutingConfig(
            List<ParsedModelRouteBlock> modelRoutes,
            QueryClassificationSection queryClassification
    ) {
    }

    private record ParsedModelRouteBlock(
            ModelRouteConfigItem item,
            String extras,
            int start,
            int end
    ) {
    }

    private record ParsedQueryClassificationRuleBlock(
            QueryClassificationRuleConfigItem item,
            String extras
    ) {
    }

    private record QueryClassificationSection(
            boolean enabled,
            String sectionExtras,
            SectionRange region,
            List<ParsedQueryClassificationRuleBlock> rules
    ) {
    }

    private record HeaderRange(
            String header,
            boolean array,
            int start,
            int end
    ) {
    }

    private record SectionRange(
            int start,
            int end
    ) {
    }

    private record PropertyValue(
            String name,
            String rawValue,
            int start,
            int end
    ) {
    }
}
