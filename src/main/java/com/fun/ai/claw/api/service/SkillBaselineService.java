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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

@Service
public class SkillBaselineService {
    private static final String SERVER_PACKAGE_SOURCE_TYPE = "SERVER_PACKAGE";

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
                .filter(record -> SERVER_PACKAGE_SOURCE_TYPE.equalsIgnoreCase(record.sourceType()))
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
        validateDisplayNameUniqueness(resolveDisplayName(skillKey, request.displayName()), null);
        return saveBaseline(skillKey, request);
    }

    public SkillBaselineResponse upsertBaseline(String skillKey, SkillBaselineUpsertRequest request) {
        String resolvedSkillKey = resolveSkillKey(skillKey, request);
        validateDisplayNameUniqueness(resolveDisplayName(resolvedSkillKey, request.displayName()), resolvedSkillKey);
        SkillBaselineResponse response = saveBaseline(resolvedSkillKey, request);
        syncAffectedInstances(resolvedSkillKey);
        return response;
    }

    @Transactional
    public SkillBaselineResponse uploadAndCreateBaseline(String skillKey,
                                                         String displayName,
                                                         String description,
                                                         Boolean enabled,
                                                         String updatedBy,
                                                         byte[] zipBytes) {
        if (zipBytes == null || zipBytes.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "zip file is required");
        }
        String normalizedSkillKey = normalizeRequiredKey(skillKey);
        if (repository.existsBySkillKey(normalizedSkillKey)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "skill baseline already exists: " + normalizedSkillKey);
        }
        String resolvedDisplayName = resolveDisplayName(normalizedSkillKey, displayName);
        validateDisplayNameUniqueness(resolvedDisplayName, null);

        planeClient.uploadSkillPackage(normalizedSkillKey, zipBytes, false);
        try {
            SkillBaselineResponse response = saveBaseline(normalizedSkillKey, new SkillBaselineUpsertRequest(
                    normalizedSkillKey,
                    resolvedDisplayName,
                    description,
                    SERVER_PACKAGE_SOURCE_TYPE,
                    normalizedSkillKey,
                    enabled,
                    updatedBy
            ));
            return response;
        } catch (RuntimeException ex) {
            planeClient.deleteSkillPackage(normalizedSkillKey);
            throw ex;
        }
    }

    public void deleteBaseline(String skillKey) {
        String normalizedSkillKey = normalizeRequiredKey(skillKey);
        SkillBaselineRecord existing = repository.findBySkillKey(normalizedSkillKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "skill baseline not found: " + skillKey));
        List<java.util.UUID> affectedInstanceIds = instanceSkillBindingRepository.findInstanceIdsBySkillKey(normalizedSkillKey);
        if (repository.deleteBySkillKey(normalizedSkillKey) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "skill baseline not found: " + skillKey);
        }
        if (SERVER_PACKAGE_SOURCE_TYPE.equalsIgnoreCase(existing.sourceType())
                && normalizedSkillKey.equals(trimToNull(existing.sourceRef()))) {
            planeClient.deleteSkillPackage(normalizedSkillKey);
        }
        syncInstances(affectedInstanceIds);
    }

    private SkillBaselineResponse saveBaseline(String skillKey, SkillBaselineUpsertRequest request) {
        SkillBaselineRecord existing = repository.findBySkillKey(skillKey).orElse(null);
        Instant now = Instant.now();
        String sourceType = normalizeSkillSourceType(request.sourceType());
        String sourceRef = normalizeRequiredSourceRef(request.sourceRef());

        SkillBaselineRecord record = new SkillBaselineRecord(
                skillKey,
                firstNonBlank(request.displayName(), skillKey),
                trimToNull(request.description()),
                sourceType,
                sourceRef,
                request.enabled() != null ? request.enabled() : true,
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
                0,
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
                record.updatedBy(),
                record.createdAt(),
                record.updatedAt()
        );
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

    private String normalizeSkillSourceType(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return SERVER_PACKAGE_SOURCE_TYPE;
        }
        if (!SERVER_PACKAGE_SOURCE_TYPE.equalsIgnoreCase(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "unsupported skill sourceType: " + normalized + ", expected " + SERVER_PACKAGE_SOURCE_TYPE);
        }
        return SERVER_PACKAGE_SOURCE_TYPE;
    }

    private String normalizeRequiredSourceRef(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sourceRef is required");
        }
        return normalized;
    }

    private String resolveDisplayName(String skillKey, String displayName) {
        return firstNonBlank(displayName, skillKey);
    }

    private void validateDisplayNameUniqueness(String displayName, String currentSkillKey) {
        if (!StringUtils.hasText(displayName)) {
            return;
        }
        boolean duplicated = currentSkillKey == null
                ? repository.existsByDisplayNameIgnoreCase(displayName)
                : repository.existsByDisplayNameIgnoreCaseExcludingSkillKey(displayName, currentSkillKey);
        if (duplicated) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "skill displayName already exists: " + displayName);
        }
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
