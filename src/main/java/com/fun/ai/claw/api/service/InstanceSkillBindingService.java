package com.fun.ai.claw.api.service;

import com.fun.ai.claw.api.model.InstanceSkillBindingRecord;
import com.fun.ai.claw.api.model.InstanceSkillBindingResponse;
import com.fun.ai.claw.api.model.ListResponse;
import com.fun.ai.claw.api.model.SkillBaselineRecord;
import com.fun.ai.claw.api.model.UpsertInstanceSkillBindingRequest;
import com.fun.ai.claw.api.repository.InstanceRepository;
import com.fun.ai.claw.api.repository.InstanceSkillBindingRepository;
import com.fun.ai.claw.api.repository.SkillBaselineRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class InstanceSkillBindingService {

    private final InstanceRepository instanceRepository;
    private final SkillBaselineRepository skillBaselineRepository;
    private final InstanceSkillBindingRepository instanceSkillBindingRepository;
    private final PlaneClient planeClient;
    private final InstanceConfigMutationService instanceConfigMutationService;

    public InstanceSkillBindingService(InstanceRepository instanceRepository,
                                       SkillBaselineRepository skillBaselineRepository,
                                       InstanceSkillBindingRepository instanceSkillBindingRepository,
                                       PlaneClient planeClient,
                                       InstanceConfigMutationService instanceConfigMutationService) {
        this.instanceRepository = instanceRepository;
        this.skillBaselineRepository = skillBaselineRepository;
        this.instanceSkillBindingRepository = instanceSkillBindingRepository;
        this.planeClient = planeClient;
        this.instanceConfigMutationService = instanceConfigMutationService;
    }

    public ListResponse<InstanceSkillBindingResponse> listBindings(UUID instanceId) {
        requireInstance(instanceId);
        List<InstanceSkillBindingResponse> items = instanceSkillBindingRepository.findByInstanceId(instanceId).stream()
                .map(this::toResponse)
                .toList();
        return new ListResponse<>(items);
    }

    public InstanceSkillBindingResponse install(UUID instanceId,
                                                String skillKey,
                                                UpsertInstanceSkillBindingRequest request) {
        requireInstance(instanceId);
        String normalizedSkillKey = normalizeRequiredSkillKey(skillKey);
        SkillBaselineRecord baseline = skillBaselineRepository.findBySkillKey(normalizedSkillKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "skill baseline not found: " + skillKey));
        if (!baseline.enabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "skill baseline is disabled: " + normalizedSkillKey);
        }

        Instant now = Instant.now();
        InstanceSkillBindingRecord existing = instanceSkillBindingRepository.findByInstanceId(instanceId).stream()
                .filter(item -> normalizedSkillKey.equals(item.skillKey()))
                .findFirst()
                .orElse(null);
        instanceSkillBindingRepository.upsert(new InstanceSkillBindingRecord(
                instanceId,
                normalizedSkillKey,
                trimToNull(request == null ? null : request.updatedBy()),
                existing != null ? existing.createdAt() : now,
                now
        ), now);
        syncInstance(instanceId);
        return toResponse(instanceSkillBindingRepository.findByInstanceId(instanceId).stream()
                .filter(item -> normalizedSkillKey.equals(item.skillKey()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "failed to load saved instance skill binding: " + normalizedSkillKey)));
    }

    public void uninstall(UUID instanceId, String skillKey) {
        requireInstance(instanceId);
        String normalizedSkillKey = normalizeRequiredSkillKey(skillKey);
        instanceSkillBindingRepository.delete(instanceId, normalizedSkillKey);
        syncInstance(instanceId);
    }

    private void syncInstance(UUID instanceId) {
        planeClient.syncInstanceSkills(instanceId);
        instanceConfigMutationService.applyResolvedRuntimeConfigIfRunning(instanceId);
    }

    private InstanceSkillBindingResponse toResponse(InstanceSkillBindingRecord record) {
        SkillBaselineRecord baseline = skillBaselineRepository.findBySkillKey(record.skillKey())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "skill baseline not found: " + record.skillKey()));
        return new InstanceSkillBindingResponse(
                record.instanceId(),
                record.skillKey(),
                baseline.displayName(),
                baseline.description(),
                baseline.enabled(),
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

    private String normalizeRequiredSkillKey(String skillKey) {
        if (!StringUtils.hasText(skillKey)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "skillKey is required");
        }
        return skillKey.trim();
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim();
        return normalized.length() <= 128 ? normalized : normalized.substring(0, 128);
    }
}
