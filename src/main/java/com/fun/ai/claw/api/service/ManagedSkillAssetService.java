package com.fun.ai.claw.api.service;

import com.fun.ai.claw.api.model.ManagedSkillAssetPayload;
import com.fun.ai.claw.api.model.SkillBaselineRecord;
import com.fun.ai.claw.api.repository.InstanceRepository;
import com.fun.ai.claw.api.repository.InstanceSkillBindingRepository;
import com.fun.ai.claw.api.repository.SkillBaselineRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class ManagedSkillAssetService {

    private final InstanceRepository instanceRepository;
    private final InstanceSkillBindingRepository instanceSkillBindingRepository;
    private final SkillBaselineRepository skillBaselineRepository;

    public ManagedSkillAssetService(InstanceRepository instanceRepository,
                                    InstanceSkillBindingRepository instanceSkillBindingRepository,
                                    SkillBaselineRepository skillBaselineRepository) {
        this.instanceRepository = instanceRepository;
        this.instanceSkillBindingRepository = instanceSkillBindingRepository;
        this.skillBaselineRepository = skillBaselineRepository;
    }

    public List<ManagedSkillAssetPayload> listEnabledByInstanceId(UUID instanceId) {
        if (instanceRepository.findById(instanceId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "instance not found: " + instanceId);
        }
        return instanceSkillBindingRepository.findByInstanceId(instanceId).stream()
                .map(binding -> skillBaselineRepository.findBySkillKey(binding.skillKey()).orElse(null))
                .filter(Objects::nonNull)
                .filter(SkillBaselineRecord::enabled)
                .sorted(Comparator.comparing(SkillBaselineRecord::skillKey))
                .map(skill -> new ManagedSkillAssetPayload(skill.skillKey(), skill.skillMd()))
                .toList();
    }
}
