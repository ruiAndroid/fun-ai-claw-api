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
import java.util.LinkedHashSet;
import java.util.List;

@Service
public class AgentBaselineService {

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
        Instant now = Instant.now();

        AgentBaselineRecord record = new AgentBaselineRecord(
                agentKey,
                firstNonBlank(request.displayName(), agentKey),
                trimToNull(request.description()),
                firstNonBlank(request.runtime(), "zeroclaw"),
                firstNonBlank(request.sourceType(), "MANUAL"),
                trimToNull(request.sourceRef()),
                request.enabled() != null ? request.enabled() : true,
                trimToNull(request.provider()),
                trimToNull(request.model()),
                request.temperature(),
                request.agentic(),
                writeStringList(normalizeList(request.allowedTools())),
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
                allowedTools.size(),
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
                record.provider(),
                record.model(),
                record.temperature(),
                record.agentic(),
                parseStringList(record.allowedToolsJson()),
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
        if (normalized == null || "[]".equals(normalized)) {
            return List.of();
        }
        List<String> values = new java.util.ArrayList<>();
        String body = normalized;
        if (body.startsWith("[") && body.endsWith("]")) {
            body = body.substring(1, body.length() - 1);
        }
        if (body.isBlank()) {
            return List.of();
        }
        for (String part : body.split(",")) {
            String value = part.trim();
            if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                value = value.substring(1, value.length() - 1);
            }
            value = value.replace("\\\"", "\"").replace("\\\\", "\\");
            if (StringUtils.hasText(value)) {
                values.add(value);
            }
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
            builder.append('"')
                    .append(normalized.get(index).replace("\\", "\\\\").replace("\"", "\\\""))
                    .append('"');
        }
        builder.append(']');
        return builder.toString();
    }
}
