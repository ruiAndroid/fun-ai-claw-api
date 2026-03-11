package com.fun.ai.claw.api.service;

import com.fun.ai.claw.api.model.AgentBaselineRecord;
import com.fun.ai.claw.api.model.AgentBaselineResponse;
import com.fun.ai.claw.api.model.AgentBaselineSummaryResponse;
import com.fun.ai.claw.api.model.AgentBaselineUpsertRequest;
import com.fun.ai.claw.api.model.ListResponse;
import com.fun.ai.claw.api.repository.AgentBaselineRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AgentBaselineService {

    private static final Pattern JSON_ARRAY_ITEM_PATTERN = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"");

    private final AgentBaselineRepository repository;

    public AgentBaselineService(AgentBaselineRepository repository) {
        this.repository = repository;
    }

    public ListResponse<AgentBaselineSummaryResponse> listBaselines() {
        List<AgentBaselineSummaryResponse> items = repository.findAll().stream()
                .map(this::toSummary)
                .toList();
        return new ListResponse<>(items);
    }

    public AgentBaselineResponse getBaseline(String agentKey) {
        return repository.findByAgentKey(normalizeRequiredKey(agentKey))
                .map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "agent baseline not found: " + agentKey));
    }

    @Transactional
    public AgentBaselineResponse createBaseline(AgentBaselineUpsertRequest request) {
        String agentKey = resolveAgentKey(null, request);
        if (repository.existsByAgentKey(agentKey)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "agent baseline already exists: " + agentKey);
        }
        return saveBaseline(agentKey, request);
    }

    @Transactional
    public AgentBaselineResponse upsertBaseline(String agentKey, AgentBaselineUpsertRequest request) {
        return saveBaseline(resolveAgentKey(agentKey, request), request);
    }

    @Transactional
    public void deleteBaseline(String agentKey) {
        if (repository.deleteByAgentKey(normalizeRequiredKey(agentKey)) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "agent baseline not found: " + agentKey);
        }
    }

    private AgentBaselineResponse saveBaseline(String agentKey, AgentBaselineUpsertRequest request) {
        AgentBaselineRecord existing = repository.findByAgentKey(agentKey).orElse(null);
        String normalizedManifestJson = normalizeManifestJson(request.manifestJson());
        String manifestAgentId = trimToNull(extractJsonStringField(normalizedManifestJson, "agent_id"));
        if (StringUtils.hasText(manifestAgentId) && !agentKey.equals(manifestAgentId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "manifest agent_id does not match target agent key: " + manifestAgentId);
        }

        List<String> normalizedSkillIds = normalizeList(request.skillIds());
        if (normalizedSkillIds.isEmpty()) {
            normalizedSkillIds = extractJsonStringArray(normalizedManifestJson, "skills");
        }

        String runtime = firstNonBlank(request.runtime(), extractJsonStringField(normalizedManifestJson, "runtime"), "zeroclaw");
        String displayName = firstNonBlank(request.displayName(), agentKey);
        String sourceType = firstNonBlank(request.sourceType(), "MANUAL");
        Boolean enabledValue = request.enabled() != null ? request.enabled() : true;
        Boolean agenticValue = request.agentic() != null ? request.agentic() : extractJsonBooleanField(normalizedManifestJson, "agentic");
        String entrySkill = firstNonBlank(request.entrySkill(), extractJsonStringField(normalizedManifestJson, "entry_skill"));
        List<String> normalizedAllowedTools = normalizeList(request.allowedTools());
        Instant now = Instant.now();

        AgentBaselineRecord record = new AgentBaselineRecord(
                agentKey,
                displayName,
                trimToNull(request.description()),
                runtime,
                sourceType,
                trimToNull(request.sourceRef()),
                enabledValue,
                normalizedManifestJson,
                trimToNull(request.mainAgentsMd()),
                trimToNull(request.provider()),
                trimToNull(request.model()),
                request.temperature(),
                agenticValue,
                entrySkill,
                writeStringList(normalizedAllowedTools),
                writeStringList(normalizedSkillIds),
                trimToNull(request.systemPrompt()),
                trimToNull(request.updatedBy()),
                existing != null ? existing.createdAt() : now,
                now
        );

        repository.upsert(record, now);
        return repository.findByAgentKey(agentKey)
                .map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "failed to load saved agent baseline: " + agentKey));
    }

    private AgentBaselineSummaryResponse toSummary(AgentBaselineRecord record) {
        List<String> allowedTools = parseStringList(record.allowedToolsJson());
        List<String> skillIds = parseStringList(record.skillIdsJson());
        return new AgentBaselineSummaryResponse(
                record.agentKey(),
                record.displayName(),
                record.runtime(),
                record.sourceType(),
                record.sourceRef(),
                record.enabled(),
                record.provider(),
                record.model(),
                record.temperature(),
                record.agentic(),
                record.entrySkill(),
                allowedTools.size(),
                skillIds.size(),
                record.updatedBy(),
                record.createdAt(),
                record.updatedAt()
        );
    }

    private AgentBaselineResponse toResponse(AgentBaselineRecord record) {
        return new AgentBaselineResponse(
                record.agentKey(),
                record.displayName(),
                record.description(),
                record.runtime(),
                record.sourceType(),
                record.sourceRef(),
                record.enabled(),
                record.manifestJson(),
                record.mainAgentsMd(),
                record.provider(),
                record.model(),
                record.temperature(),
                record.agentic(),
                record.entrySkill(),
                parseStringList(record.allowedToolsJson()),
                parseStringList(record.skillIdsJson()),
                record.systemPrompt(),
                record.updatedBy(),
                record.createdAt(),
                record.updatedAt()
        );
    }

    private String resolveAgentKey(String explicitAgentKey, AgentBaselineUpsertRequest request) {
        String agentKey = normalizeKey(explicitAgentKey);
        if (agentKey != null) {
            String requestAgentKey = normalizeKey(request.agentKey());
            if (requestAgentKey != null && !agentKey.equals(requestAgentKey)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "request agentKey does not match target path: " + requestAgentKey);
            }
            return agentKey;
        }

        String requestAgentKey = normalizeKey(request.agentKey());
        if (requestAgentKey != null) {
            return requestAgentKey;
        }

        String manifestAgentId = normalizeKey(extractJsonStringField(normalizeManifestJson(request.manifestJson()), "agent_id"));
        if (manifestAgentId != null) {
            return manifestAgentId;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "agentKey is required");
    }

    private String normalizeRequiredKey(String agentKey) {
        String normalized = normalizeKey(agentKey);
        if (normalized == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "agentKey is required");
        }
        return normalized;
    }

    private String normalizeKey(String value) {
        return trimToNull(value);
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            String normalized = trimToNull(candidate);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private String normalizeManifestJson(String manifestJson) {
        String normalized = trimToNull(manifestJson);
        if (normalized == null) {
            return null;
        }
        if (!(normalized.startsWith("{") && normalized.endsWith("}"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "manifest_json must be a JSON object");
        }
        return normalized;
    }

    private String extractJsonStringField(String json, String fieldName) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
                .matcher(json);
        if (!matcher.find()) {
            return null;
        }
        return unescapeJsonString(matcher.group(1));
    }

    private Boolean extractJsonBooleanField(String json, String fieldName) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*(true|false)")
                .matcher(json);
        if (!matcher.find()) {
            return null;
        }
        return Boolean.parseBoolean(matcher.group(1));
    }

    private List<String> extractJsonStringArray(String json, String fieldName) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL)
                .matcher(json);
        if (!matcher.find()) {
            return List.of();
        }
        return parseStringList("[" + matcher.group(1) + "]");
    }

    private List<String> normalizeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        for (String value : values) {
            String normalized = trimToNull(value);
            if (normalized != null) {
                ordered.add(normalized);
            }
        }
        return List.copyOf(ordered);
    }

    private List<String> parseStringList(String rawJson) {
        String normalized = trimToNull(rawJson);
        if (normalized == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        Matcher matcher = JSON_ARRAY_ITEM_PATTERN.matcher(normalized);
        while (matcher.find()) {
            values.add(unescapeJsonString(matcher.group(1)));
        }
        return normalizeList(values);
    }

    private String writeStringList(List<String> values) {
        List<String> normalized = normalizeList(values);
        if (normalized.isEmpty()) {
            return "[]";
        }
        StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < normalized.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append('"').append(escapeJsonString(normalized.get(index))).append('"');
        }
        builder.append(']');
        return builder.toString();
    }

    private String escapeJsonString(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }

    private String unescapeJsonString(String value) {
        StringBuilder builder = new StringBuilder();
        boolean escaping = false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (escaping) {
                switch (current) {
                    case '"' -> builder.append('"');
                    case '\\' -> builder.append('\\');
                    case '/' -> builder.append('/');
                    case 'b' -> builder.append('\b');
                    case 'f' -> builder.append('\f');
                    case 'n' -> builder.append('\n');
                    case 'r' -> builder.append('\r');
                    case 't' -> builder.append('\t');
                    case 'u' -> {
                        if (index + 4 < value.length()) {
                            String hex = value.substring(index + 1, index + 5);
                            try {
                                builder.append((char) Integer.parseInt(hex, 16));
                                index += 4;
                            } catch (NumberFormatException exception) {
                                builder.append("\\u").append(hex);
                                index += 4;
                            }
                        } else {
                            builder.append("\\u");
                        }
                    }
                    default -> builder.append(current);
                }
                escaping = false;
                continue;
            }
            if (current == '\\') {
                escaping = true;
            } else {
                builder.append(current);
            }
        }
        if (escaping) {
            builder.append('\\');
        }
        return builder.toString();
    }
}
