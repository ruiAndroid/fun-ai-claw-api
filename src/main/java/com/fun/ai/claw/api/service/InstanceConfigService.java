package com.fun.ai.claw.api.service;

import com.fun.ai.claw.api.config.InstanceConfigProperties;
import com.fun.ai.claw.api.model.InstanceConfigResponse;
import com.fun.ai.claw.api.model.UpsertInstanceConfigRequest;
import com.fun.ai.claw.api.repository.InstanceRepository;
import com.fun.ai.claw.api.repository.InstanceRuntimeConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class InstanceConfigService {
    private static final Logger log = LoggerFactory.getLogger(InstanceConfigService.class);

    private final InstanceRepository instanceRepository;
    private final InstanceRuntimeConfigRepository instanceRuntimeConfigRepository;
    private final InstanceConfigProperties properties;
    private final ResourceLoader resourceLoader;
    private final InstanceManagedSkillsConfigService instanceManagedSkillsConfigService;

    public InstanceConfigService(InstanceRepository instanceRepository,
                                 InstanceRuntimeConfigRepository instanceRuntimeConfigRepository,
                                 InstanceConfigProperties properties,
                                 ResourceLoader resourceLoader,
                                 InstanceManagedSkillsConfigService instanceManagedSkillsConfigService) {
        this.instanceRepository = instanceRepository;
        this.instanceRuntimeConfigRepository = instanceRuntimeConfigRepository;
        this.properties = properties;
        this.resourceLoader = resourceLoader;
        this.instanceManagedSkillsConfigService = instanceManagedSkillsConfigService;
    }

    public InstanceConfigResponse get(UUID instanceId) {
        requireInstance(instanceId);
        Optional<InstanceRuntimeConfigRepository.Row> overrideRow = instanceRuntimeConfigRepository.findByInstanceId(instanceId);
        return buildResponse(instanceId, overrideRow.orElse(null));
    }

    public InstanceConfigResponse upsert(UUID instanceId, UpsertInstanceConfigRequest request) {
        requireInstance(instanceId);
        if (request == null || !StringUtils.hasText(request.configToml())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "configToml must not be blank");
        }
        String normalizedConfig = normalizeManagedConfig(request.configToml());
        validateConfigSize(normalizedConfig, "instance config");
        String updatedBy = normalizeUpdatedBy(request.updatedBy());
        Instant updatedAt = Instant.now();
        instanceRuntimeConfigRepository.upsert(instanceId, normalizedConfig, updatedBy, updatedAt);
        return buildResponse(instanceId, new InstanceRuntimeConfigRepository.Row(instanceId, normalizedConfig, updatedBy, updatedAt));
    }

    public InstanceConfigResponse deleteOverride(UUID instanceId) {
        requireInstance(instanceId);
        instanceRuntimeConfigRepository.deleteByInstanceId(instanceId);
        return buildResponse(instanceId, null);
    }

    public RuntimeConfig resolveRuntimeConfig(UUID instanceId) {
        requireInstance(instanceId);
        Optional<InstanceRuntimeConfigRepository.Row> overrideRow = instanceRuntimeConfigRepository.findByInstanceId(instanceId);
        if (overrideRow.isPresent() && StringUtils.hasText(overrideRow.get().configToml())) {
            return new RuntimeConfig(
                    normalizeManagedConfig(overrideRow.get().configToml()),
                    "INSTANCE_OVERRIDE",
                    safePath(properties.getRuntimeConfigPath(), "/data/zeroclaw/config.toml"),
                    properties.isOverwriteOnStart()
            );
        }
        return new RuntimeConfig(
                loadDefaultTemplate(),
                "DEFAULT_TEMPLATE",
                safePath(properties.getRuntimeConfigPath(), "/data/zeroclaw/config.toml"),
                properties.isOverwriteOnStart()
        );
    }

    private void requireInstance(UUID instanceId) {
        if (instanceRepository.findById(instanceId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "instance not found: " + instanceId);
        }
    }

    private InstanceConfigResponse buildResponse(UUID instanceId, InstanceRuntimeConfigRepository.Row overrideRow) {
        String source;
        String configToml;
        boolean overrideExists = overrideRow != null;
        if (overrideRow != null && StringUtils.hasText(overrideRow.configToml())) {
            source = "INSTANCE_OVERRIDE";
            configToml = normalizeManagedConfig(overrideRow.configToml());
        } else {
            source = "DEFAULT_TEMPLATE";
            configToml = normalizeManagedConfig(loadDefaultTemplate());
        }

        return new InstanceConfigResponse(
                instanceId,
                safePath(properties.getRuntimeConfigPath(), "/data/zeroclaw/config.toml"),
                source,
                configToml,
                properties.isOverwriteOnStart(),
                overrideExists,
                safePath(properties.getDefaultConfigTomlPath(), "classpath:templates/default-instance-config.toml"),
                overrideRow == null ? null : overrideRow.updatedAt(),
                overrideRow == null ? null : overrideRow.updatedBy()
        );
    }

    private String loadDefaultTemplate() {
        String configuredPath = safePath(properties.getDefaultConfigTomlPath(), "");
        if (StringUtils.hasText(configuredPath)) {
            try {
                Resource resource = resourceLoader.getResource(configuredPath);
                if (resource.exists()) {
                    String fromResource = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    if (StringUtils.hasText(fromResource)) {
                        String normalized = normalizeConfigToml(fromResource);
                        validateConfigSize(normalized, "default config template");
                        return normalized;
                    }
                } else {
                    log.warn("default config template resource not found: {}", configuredPath);
                }
            } catch (IOException ex) {
                log.warn("failed to read default config template from {}: {}", configuredPath, ex.getMessage());
            } catch (RuntimeException ex) {
                log.warn("invalid default config template path {}: {}", configuredPath, ex.getMessage());
            }
        }
        if (StringUtils.hasText(properties.getDefaultConfigToml())) {
            String normalized = normalizeConfigToml(properties.getDefaultConfigToml());
            validateConfigSize(normalized, "default config template");
            return normalized;
        }
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "default instance config template is not configured");
    }

    private void validateConfigSize(String configToml, String source) {
        int sizeBytes = configToml.getBytes(StandardCharsets.UTF_8).length;
        int maxBytes = resolveMaxBytes();
        if (sizeBytes > maxBytes) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    source + " exceeds size limit: " + sizeBytes + " bytes > " + maxBytes + " bytes");
        }
    }

    private int resolveMaxBytes() {
        return properties.getMaxBytes() > 0 ? properties.getMaxBytes() : 524288;
    }

    private String normalizeConfigToml(String configToml) {
        String normalized = configToml == null ? "" : configToml.replace("\r\n", "\n").trim();
        if (!StringUtils.hasText(normalized)) {
            return "";
        }
        return normalized + "\n";
    }

    private String normalizeManagedConfig(String configToml) {
        String normalized = normalizeConfigToml(configToml);
        return instanceManagedSkillsConfigService.applyPolicy(normalized);
    }

    private String safePath(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private String normalizeUpdatedBy(String updatedBy) {
        String normalized = StringUtils.hasText(updatedBy) ? updatedBy.trim() : "ui";
        if (normalized.length() <= 128) {
            return normalized;
        }
        return normalized.substring(0, 128);
    }

    public record RuntimeConfig(
            String content,
            String source,
            String runtimeConfigPath,
            boolean overwriteOnStart
    ) {
    }
}
