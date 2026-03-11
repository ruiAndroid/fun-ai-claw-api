package com.fun.ai.claw.api.service;

import com.fun.ai.claw.api.config.InstanceConfigProperties;
import com.fun.ai.claw.api.model.ClawInstanceDto;
import com.fun.ai.claw.api.model.InstanceConfigResponse;
import com.fun.ai.claw.api.model.InstanceStatus;
import com.fun.ai.claw.api.model.UpsertInstanceConfigRequest;
import com.fun.ai.claw.api.repository.InstanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Service
public class InstanceConfigMutationService {
    private static final Logger log = LoggerFactory.getLogger(InstanceConfigMutationService.class);

    private final InstanceConfigService instanceConfigService;
    private final InstanceRepository instanceRepository;
    private final PlaneClient planeClient;
    private final InstanceConfigProperties properties;

    public InstanceConfigMutationService(InstanceConfigService instanceConfigService,
                                         InstanceRepository instanceRepository,
                                         PlaneClient planeClient,
                                         InstanceConfigProperties properties) {
        this.instanceConfigService = instanceConfigService;
        this.instanceRepository = instanceRepository;
        this.planeClient = planeClient;
        this.properties = properties;
    }

    @Transactional
    public InstanceConfigResponse upsert(UUID instanceId, UpsertInstanceConfigRequest request) {
        InstanceConfigResponse response = instanceConfigService.upsert(instanceId, request);
        applyRuntimeConfigIfRunning(instanceId, response.configToml(), response.runtimeConfigPath());
        return response;
    }

    @Transactional
    public InstanceConfigResponse deleteOverride(UUID instanceId) {
        InstanceConfigResponse response = instanceConfigService.deleteOverride(instanceId);
        applyRuntimeConfigIfRunning(instanceId, response.configToml(), response.runtimeConfigPath());
        return response;
    }

    public void applyResolvedRuntimeConfigIfRunning(UUID instanceId) {
        InstanceConfigService.RuntimeConfig runtimeConfig = instanceConfigService.resolveRuntimeConfig(instanceId);
        applyRuntimeConfigIfRunning(instanceId, runtimeConfig.content(), runtimeConfig.runtimeConfigPath());
    }

    private void applyRuntimeConfigIfRunning(UUID instanceId, String configToml, String runtimeConfigPath) {
        ClawInstanceDto instance = instanceRepository.findById(instanceId).orElse(null);
        if (instance == null) {
            return;
        }
        if (instance.status() != InstanceStatus.RUNNING && instance.status() != InstanceStatus.CREATING) {
            log.info("skip runtime config apply for instance {} because status is {}", instanceId, instance.status());
            return;
        }
        String resolvedRuntimeConfigPath = StringUtils.hasText(runtimeConfigPath)
                ? runtimeConfigPath.trim()
                : (StringUtils.hasText(properties.getRuntimeConfigPath())
                ? properties.getRuntimeConfigPath().trim()
                : "/data/zeroclaw/config.toml");
        planeClient.writeRuntimeFile(
                instanceId,
                resolvedRuntimeConfigPath,
                (configToml == null ? "" : configToml).getBytes(StandardCharsets.UTF_8),
                true
        );
        log.info("applied runtime config immediately for instance {} at {}", instanceId, resolvedRuntimeConfigPath);
    }
}
