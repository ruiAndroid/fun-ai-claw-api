package com.fun.ai.claw.api.service;

import com.fun.ai.claw.api.model.ListResponse;
import com.fun.ai.claw.api.model.SkillBaselineRecord;
import com.fun.ai.claw.api.model.SkillBaselineResponse;
import com.fun.ai.claw.api.model.SkillBaselineSummaryResponse;
import com.fun.ai.claw.api.model.SkillBaselineUpsertRequest;
import com.fun.ai.claw.api.repository.InstanceSkillBindingRepository;
import com.fun.ai.claw.api.repository.SkillBaselineRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

@Service
public class SkillBaselineService {

    private final SkillBaselineRepository repository;
    private final InstanceSkillBindingRepository instanceSkillBindingRepository;
    private final PlaneClient planeClient;
    private final InstanceConfigMutationService instanceConfigMutationService;

    public SkillBaselineService(SkillBaselineRepository repository,
                                InstanceSkillBindingRepository instanceSkillBindingRepository,
                                PlaneClient planeClient,
                                InstanceConfigMutationService instanceConfigMutationService) {
        this.repository = repository;
        this.instanceSkillBindingRepository = instanceSkillBindingRepository;
        this.planeClient = planeClient;
        this.instanceConfigMutationService = instanceConfigMutationService;
    }

    public ListResponse<SkillBaselineSummaryResponse> listBaselines() {
        List<SkillBaselineSummaryResponse> items = repository.findAll().stream()
                .map(this::toSummary)
                .toList();
        return new ListResponse<>(items);
    }

    public SkillBaselineResponse getBaseline(String skillKey) {
        return repository.findBySkillKey(normalizeRequiredKey(skillKey))
                .map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "skill baseline not found: " + skillKey));
    }

    public SkillBaselineResponse createBaseline(SkillBaselineUpsertRequest request) {
        String skillKey = resolveSkillKey(null, request);
        if (repository.existsBySkillKey(skillKey)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "skill baseline already exists: " + skillKey);
        }
        return saveBaseline(skillKey, request);
    }

    public SkillBaselineResponse upsertBaseline(String skillKey, SkillBaselineUpsertRequest request) {
        String resolvedSkillKey = resolveSkillKey(skillKey, request);
        SkillBaselineResponse response = saveBaseline(resolvedSkillKey, request);
        syncAffectedInstances(resolvedSkillKey);
        return response;
    }

    public void deleteBaseline(String skillKey) {
        String normalizedSkillKey = normalizeRequiredKey(skillKey);
        List<java.util.UUID> affectedInstanceIds = instanceSkillBindingRepository.findInstanceIdsBySkillKey(normalizedSkillKey);
        if (repository.deleteBySkillKey(normalizedSkillKey) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "skill baseline not found: " + skillKey);
        }
        syncInstances(affectedInstanceIds);
    }

    private SkillBaselineResponse saveBaseline(String skillKey, SkillBaselineUpsertRequest request) {
        SkillBaselineRecord existing = repository.findBySkillKey(skillKey).orElse(null);
        Instant now = Instant.now();

        SkillBaselineRecord record = new SkillBaselineRecord(
                skillKey,
                firstNonBlank(request.displayName(), skillKey),
                trimToNull(request.description()),
                firstNonBlank(request.sourceType(), "MANUAL"),
                trimToNull(request.sourceRef()),
                request.enabled() != null ? request.enabled() : true,
                request.skillMd() != null ? request.skillMd() : "",
                trimToNull(request.updatedBy()),
                existing != null ? existing.createdAt() : now,
                now
        );

        repository.upsert(record, now);
        return repository.findBySkillKey(skillKey)
                .map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "failed to load saved skill baseline: " + skillKey));
    }

    private void syncAffectedInstances(String skillKey) {
        syncInstances(instanceSkillBindingRepository.findInstanceIdsBySkillKey(skillKey));
    }

    private void syncInstances(List<java.util.UUID> instanceIds) {
        for (java.util.UUID instanceId : instanceIds) {
            planeClient.syncInstanceSkills(instanceId);
            instanceConfigMutationService.applyResolvedRuntimeConfigIfRunning(instanceId);
        }
    }

    private SkillBaselineSummaryResponse toSummary(SkillBaselineRecord record) {
        return new SkillBaselineSummaryResponse(
                record.skillKey(),
                record.displayName(),
                record.description(),
                record.sourceType(),
                record.sourceRef(),
                record.enabled(),
                countLines(record.skillMd()),
                record.updatedBy(),
                record.createdAt(),
                record.updatedAt()
        );
    }

    private SkillBaselineResponse toResponse(SkillBaselineRecord record) {
        return new SkillBaselineResponse(
                record.skillKey(),
                record.displayName(),
                record.description(),
                record.sourceType(),
                record.sourceRef(),
                record.enabled(),
                record.skillMd(),
                record.updatedBy(),
                record.createdAt(),
                record.updatedAt()
        );
    }

    private int countLines(String value) {
        if (!StringUtils.hasText(value)) {
            return 0;
        }
        return value.split("\\R", -1).length;
    }

    private String resolveSkillKey(String explicitSkillKey, SkillBaselineUpsertRequest request) {
        String skillKey = normalizeKey(explicitSkillKey);
        if (skillKey != null) {
            String requestSkillKey = normalizeKey(request.skillKey());
            if (requestSkillKey != null && !skillKey.equals(requestSkillKey)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "request skillKey does not match target path: " + requestSkillKey);
            }
            return skillKey;
        }

        String requestSkillKey = normalizeKey(request.skillKey());
        if (requestSkillKey != null) {
            return requestSkillKey;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "skillKey is required");
    }

    private String normalizeRequiredKey(String skillKey) {
        String normalized = normalizeKey(skillKey);
        if (normalized == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "skillKey is required");
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
}
