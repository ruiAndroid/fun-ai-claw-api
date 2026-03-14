package com.fun.ai.claw.api.service;

import com.fun.ai.claw.api.model.AgentBaselineRecord;
import com.fun.ai.claw.api.model.InstanceAgentBindingRecord;
import com.fun.ai.claw.api.model.InstanceAgentBindingResponse;
import com.fun.ai.claw.api.model.InstanceConfigResponse;
import com.fun.ai.claw.api.model.ListResponse;
import com.fun.ai.claw.api.model.UpsertInstanceAgentBindingRequest;
import com.fun.ai.claw.api.repository.AgentBaselineRepository;
import com.fun.ai.claw.api.repository.InstanceAgentBindingRepository;
import com.fun.ai.claw.api.repository.InstanceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class InstanceAgentBindingService {

    private final InstanceRepository instanceRepository;
    private final AgentBaselineRepository agentBaselineRepository;
    private final InstanceAgentBindingRepository instanceAgentBindingRepository;
    private final InstanceManagedAgentsConfigService instanceManagedAgentsConfigService;
    private final InstanceConfigService instanceConfigService;
    private final InstanceConfigMutationService instanceConfigMutationService;

    public InstanceAgentBindingService(InstanceRepository instanceRepository,
                                       AgentBaselineRepository agentBaselineRepository,
                                       InstanceAgentBindingRepository instanceAgentBindingRepository,
                                       InstanceManagedAgentsConfigService instanceManagedAgentsConfigService,
                                       InstanceConfigService instanceConfigService,
                                       InstanceConfigMutationService instanceConfigMutationService) {
        this.instanceRepository = instanceRepository;
        this.agentBaselineRepository = agentBaselineRepository;
        this.instanceAgentBindingRepository = instanceAgentBindingRepository;
        this.instanceManagedAgentsConfigService = instanceManagedAgentsConfigService;
        this.instanceConfigService = instanceConfigService;
        this.instanceConfigMutationService = instanceConfigMutationService;
    }

    public ListResponse<InstanceAgentBindingResponse> listBindings(UUID instanceId) {
        requireInstance(instanceId);
        ensureBootstrapped(instanceId);
        List<InstanceAgentBindingResponse> items = instanceAgentBindingRepository.findByInstanceId(instanceId).stream()
                .sorted(Comparator.comparing(InstanceAgentBindingRecord::agentKey))
                .map(this::toResponse)
                .toList();
        return new ListResponse<>(items);
    }

    @Transactional
    public InstanceAgentBindingResponse upsert(UUID instanceId,
                                               String agentKey,
                                               UpsertInstanceAgentBindingRequest request) {
        requireInstance(instanceId);
        ensureBootstrapped(instanceId);
        String normalizedAgentKey = normalizeRequiredAgentKey(agentKey);
        Optional<AgentBaselineRecord> baselineOptional = agentBaselineRepository.findByAgentKey(normalizedAgentKey);
        InstanceAgentBindingRecord existing = instanceAgentBindingRepository.findByInstanceId(instanceId).stream()
                .filter(item -> normalizedAgentKey.equals(item.agentKey()))
                .findFirst()
                .orElse(null);
        if (existing == null && baselineOptional.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "agent baseline not found: " + normalizedAgentKey);
        }
        if (existing == null && baselineOptional.isPresent() && !baselineOptional.get().enabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "agent baseline is disabled: " + normalizedAgentKey);
        }

        AgentBaselineRecord baseline = baselineOptional.orElse(null);
        InstanceManagedAgentsConfigService.ManagedAgentDefaults defaults = instanceManagedAgentsConfigService.extractDefaults(
                instanceConfigService.get(instanceId).configToml()
        );
        String resolvedProvider = resolveString(
                request == null ? null : request.provider(),
                existing != null ? existing.provider() : null,
                baseline != null ? baseline.provider() : null,
                defaults.defaultProvider()
        );
        String resolvedModel = resolveString(
                request == null ? null : request.model(),
                existing != null ? existing.model() : null,
                baseline != null ? baseline.model() : null,
                defaults.defaultModel()
        );
        Instant now = Instant.now();
        InstanceAgentBindingRecord target = new InstanceAgentBindingRecord(
                instanceId,
                normalizedAgentKey,
                resolvedProvider,
                resolvedModel,
                resolveDouble(request == null ? null : request.temperature(), existing != null ? existing.temperature() : null, baseline != null ? baseline.temperature() : null),
                resolveBoolean(request == null ? null : request.agentic(), existing != null ? existing.agentic() : null, baseline != null ? baseline.agentic() : null),
                resolveNullablePrompt(request == null ? null : request.systemPrompt(), existing != null ? existing.systemPrompt() : null, baseline != null ? baseline.systemPrompt() : null),
                normalizeTools(
                        request == null ? null : request.allowedTools(),
                        existing != null ? existing.allowedTools() : null,
                        baseline != null ? baseline.allowedTools() : null
                ),
                normalizeSkills(
                        request == null ? null : request.allowedSkills(),
                        existing != null ? existing.allowedSkills() : null,
                        baseline != null ? baseline.allowedSkills() : null
                ),
                existing != null ? existing.extraConfigToml() : null,
                trimToNull(request == null ? null : request.updatedBy()),
                existing != null ? existing.createdAt() : now,
                now
        );
        instanceAgentBindingRepository.upsert(target, now);
        instanceConfigService.synchronizeManagedAgentsSource(instanceId, target.updatedBy());
        syncInstance(instanceId);
        return instanceAgentBindingRepository.findByInstanceId(instanceId).stream()
                .filter(item -> normalizedAgentKey.equals(item.agentKey()))
                .findFirst()
                .map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "failed to load saved instance agent binding: " + normalizedAgentKey));
    }

    @Transactional
    public void uninstall(UUID instanceId, String agentKey) {
        requireInstance(instanceId);
        ensureBootstrapped(instanceId);
        String normalizedAgentKey = normalizeRequiredAgentKey(agentKey);
        int deleted = instanceAgentBindingRepository.delete(instanceId, normalizedAgentKey);
        if (deleted <= 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "instance agent binding not found: " + normalizedAgentKey);
        }
        instanceConfigService.synchronizeManagedAgentsSource(instanceId, "instance-agent-uninstall");
        syncInstance(instanceId);
    }

    private void ensureBootstrapped(UUID instanceId) {
        List<InstanceAgentBindingRecord> existing = instanceAgentBindingRepository.findByInstanceId(instanceId);
        if (!existing.isEmpty()) {
            return;
        }
        InstanceConfigResponse config = instanceConfigService.get(instanceId);
        List<InstanceAgentBindingRecord> parsed = instanceManagedAgentsConfigService.parseBindings(instanceId, config.configToml());
        if (parsed.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        for (InstanceAgentBindingRecord record : parsed) {
            instanceAgentBindingRepository.upsert(new InstanceAgentBindingRecord(
                    record.instanceId(),
                    record.agentKey(),
                    record.provider(),
                    record.model(),
                    record.temperature(),
                    record.agentic(),
                    record.systemPrompt(),
                    record.allowedTools(),
                    record.allowedSkills(),
                    record.extraConfigToml(),
                    "bootstrap",
                    now,
                    now
            ), now);
        }
    }

    private void syncInstance(UUID instanceId) {
        instanceConfigMutationService.applyResolvedRuntimeConfigIfRunning(instanceId);
    }

    private InstanceAgentBindingResponse toResponse(InstanceAgentBindingRecord record) {
        AgentBaselineRecord baseline = agentBaselineRepository.findByAgentKey(record.agentKey()).orElse(null);
        return new InstanceAgentBindingResponse(
                record.instanceId(),
                record.agentKey(),
                baseline != null ? baseline.displayName() : record.agentKey(),
                baseline != null ? baseline.description() : null,
                baseline != null ? baseline.runtime() : "zeroclaw",
                baseline != null ? baseline.sourceType() : "INSTANCE_CONFIG",
                baseline != null ? baseline.sourceRef() : null,
                baseline == null || baseline.enabled(),
                record.provider(),
                record.model(),
                record.temperature(),
                record.agentic(),
                record.systemPrompt(),
                record.allowedTools(),
                record.allowedSkills(),
                record.updatedBy(),
                record.createdAt(),
                record.updatedAt()
        );
    }

    private void requireInstance(UUID instanceId) {
        if (instanceRepository.findById(instanceId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "instance not found: " + instanceId);
        }
    }

    private String normalizeRequiredAgentKey(String agentKey) {
        String normalized = trimToNull(agentKey);
        if (normalized == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "agentKey is required");
        }
        return normalized;
    }

    private String resolveString(String requestValue, String existingValue, String baselineValue) {
        return resolveString(requestValue, existingValue, baselineValue, null);
    }

    private String resolveString(String requestValue, String existingValue, String baselineValue, String defaultValue) {
        String requestNormalized = trimToNull(requestValue);
        if (requestValue != null) {
            return requestNormalized;
        }
        if (existingValue != null) {
            return existingValue;
        }
        String baselineNormalized = trimToNull(baselineValue);
        if (baselineNormalized != null) {
            return baselineNormalized;
        }
        return trimToNull(defaultValue);
    }

    private Double resolveDouble(Double requestValue, Double existingValue, Double baselineValue) {
        if (requestValue != null) {
            return requestValue;
        }
        if (existingValue != null) {
            return existingValue;
        }
        return baselineValue;
    }

    private Boolean resolveBoolean(Boolean requestValue, Boolean existingValue, Boolean baselineValue) {
        if (requestValue != null) {
            return requestValue;
        }
        if (existingValue != null) {
            return existingValue;
        }
        return baselineValue;
    }

    private String resolveNullablePrompt(String requestValue, String existingValue, String baselineValue) {
        if (requestValue != null) {
            return requestValue.replace("\r\n", "\n");
        }
        if (existingValue != null) {
            return existingValue;
        }
        return baselineValue == null ? null : baselineValue.replace("\r\n", "\n");
    }

    private List<String> normalizeTools(List<String> requestTools, List<String> existingTools, List<String> baselineTools) {
        if (requestTools == null) {
            if (existingTools != null) {
                return normalizeStringList(existingTools);
            }
            return baselineTools == null ? List.of() : normalizeStringList(baselineTools);
        }
        return normalizeStringList(requestTools);
    }

    private List<String> normalizeSkills(List<String> requestSkills, List<String> existingSkills, List<String> baselineSkills) {
        if (requestSkills == null) {
            if (existingSkills != null) {
                return normalizeStringList(existingSkills);
            }
            return baselineSkills == null ? List.of() : normalizeStringList(baselineSkills);
        }
        return normalizeStringList(requestSkills);
    }

    private List<String> normalizeStringList(List<String> values) {
        return values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }
}
