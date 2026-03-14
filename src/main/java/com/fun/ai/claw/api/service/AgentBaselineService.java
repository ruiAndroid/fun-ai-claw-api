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
import java.util.List;

@Service
public class AgentBaselineService {

    private final AgentBaselineRepository repository;
    private final AgentToolCatalogService agentToolCatalogService;

    public AgentBaselineService(AgentBaselineRepository repository,
                                AgentToolCatalogService agentToolCatalogService) {
        this.repository = repository;
        this.agentToolCatalogService = agentToolCatalogService;
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
        ToolSelectionState toolSelection = resolveToolSelection(request, existing);

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
                toolSelection.toolPresetKey(),
                toolSelection.allowedToolsExtra(),
                toolSelection.deniedTools(),
                toolSelection.allowedTools(),
                normalizeSkills(request.allowedSkills(), existing != null ? existing.allowedSkills() : null),
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
                record.toolPresetKey(),
                record.allowedToolsExtra(),
                record.deniedTools(),
                record.allowedTools(),
                record.allowedSkills(),
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
                record.toolPresetKey(),
                record.allowedToolsExtra(),
                record.deniedTools(),
                record.allowedTools(),
                record.allowedSkills(),
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

    private ToolSelectionState resolveToolSelection(AgentBaselineUpsertRequest request, AgentBaselineRecord existing) {
        ToolSelectionState existingSelection = toToolSelectionState(existing);

        boolean hasStructuredUpdate = request.toolPresetKey() != null
                || request.allowedToolsExtra() != null
                || request.deniedTools() != null;

        if (hasStructuredUpdate) {
            String toolPresetKey = request.toolPresetKey() != null
                    ? agentToolCatalogService.normalizePresetKey(request.toolPresetKey())
                    : existingSelection.toolPresetKey();
            List<String> allowedToolsExtra = request.allowedToolsExtra() != null
                    ? agentToolCatalogService.normalizeTools(request.allowedToolsExtra())
                    : existingSelection.allowedToolsExtra();
            List<String> deniedTools = request.deniedTools() != null
                    ? agentToolCatalogService.normalizeTools(request.deniedTools())
                    : existingSelection.deniedTools();
            return new ToolSelectionState(
                    toolPresetKey,
                    allowedToolsExtra,
                    deniedTools,
                    agentToolCatalogService.resolveTools(toolPresetKey, allowedToolsExtra, deniedTools)
            );
        }

        if (request.allowedTools() != null) {
            List<String> allowedToolsExtra = agentToolCatalogService.normalizeTools(request.allowedTools());
            return new ToolSelectionState(
                    null,
                    allowedToolsExtra,
                    List.of(),
                    agentToolCatalogService.resolveTools(null, allowedToolsExtra, List.of())
            );
        }

        return existingSelection;
    }

    private ToolSelectionState toToolSelectionState(AgentBaselineRecord record) {
        if (record == null) {
            return new ToolSelectionState(null, List.of(), List.of(), List.of());
        }

        String toolPresetKey = agentToolCatalogService.normalizePresetKey(record.toolPresetKey());
        List<String> allowedToolsExtra = agentToolCatalogService.normalizeTools(record.allowedToolsExtra());
        List<String> deniedTools = agentToolCatalogService.normalizeTools(record.deniedTools());

        if (toolPresetKey == null && allowedToolsExtra.isEmpty() && deniedTools.isEmpty()) {
            allowedToolsExtra = agentToolCatalogService.normalizeTools(record.allowedTools());
        }

        return new ToolSelectionState(
                toolPresetKey,
                allowedToolsExtra,
                deniedTools,
                agentToolCatalogService.resolveTools(toolPresetKey, allowedToolsExtra, deniedTools)
        );
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

    private List<String> normalizeSkills(List<String> requestSkills, List<String> existingSkills) {
        if (requestSkills != null) {
            return normalizeSkills(requestSkills);
        }
        return normalizeSkills(existingSkills);
    }

    private List<String> normalizeSkills(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
    }

    private record ToolSelectionState(
            String toolPresetKey,
            List<String> allowedToolsExtra,
            List<String> deniedTools,
            List<String> allowedTools
    ) {
    }
}
