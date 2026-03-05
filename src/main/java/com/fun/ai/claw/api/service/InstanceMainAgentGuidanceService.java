package com.fun.ai.claw.api.service;

import com.fun.ai.claw.api.config.AgentGuidanceProperties;
import com.fun.ai.claw.api.model.InstanceMainAgentGuidanceResponse;
import com.fun.ai.claw.api.model.UpsertInstanceMainAgentGuidanceRequest;
import com.fun.ai.claw.api.repository.InstanceAgentGuidanceRepository;
import com.fun.ai.claw.api.repository.InstanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class InstanceMainAgentGuidanceService {
    private static final Logger log = LoggerFactory.getLogger(InstanceMainAgentGuidanceService.class);

    private final InstanceRepository instanceRepository;
    private final InstanceAgentGuidanceRepository guidanceRepository;
    private final AgentGuidanceProperties properties;

    public InstanceMainAgentGuidanceService(InstanceRepository instanceRepository,
                                            InstanceAgentGuidanceRepository guidanceRepository,
                                            AgentGuidanceProperties properties) {
        this.instanceRepository = instanceRepository;
        this.guidanceRepository = guidanceRepository;
        this.properties = properties;
    }

    public InstanceMainAgentGuidanceResponse get(UUID instanceId) {
        requireInstance(instanceId);
        Optional<InstanceAgentGuidanceRepository.Row> overrideRow = guidanceRepository.findByInstanceId(instanceId);
        ResolvedGlobalGuidance global = resolveGlobalGuidance();
        return buildResponse(instanceId, overrideRow.orElse(null), global);
    }

    public InstanceMainAgentGuidanceResponse upsert(UUID instanceId, UpsertInstanceMainAgentGuidanceRequest request) {
        requireInstance(instanceId);
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
        }

        Optional<InstanceAgentGuidanceRepository.Row> existing = guidanceRepository.findByInstanceId(instanceId);
        String nextPrompt;
        if (request.prompt() != null) {
            if (!StringUtils.hasText(request.prompt())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "prompt must not be blank");
            }
            nextPrompt = request.prompt();
        } else if (existing.isPresent()) {
            nextPrompt = existing.get().agentsMd();
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "prompt is required for first-time save");
        }

        validatePromptSize(nextPrompt, "instance override");

        boolean nextEnabled = request.enabled() != null
                ? request.enabled()
                : existing.map(InstanceAgentGuidanceRepository.Row::enabled).orElse(true);
        String updatedBy = normalizeUpdatedBy(request.updatedBy());
        Instant now = Instant.now();
        guidanceRepository.upsert(instanceId, nextPrompt, nextEnabled, updatedBy, now);

        ResolvedGlobalGuidance global = resolveGlobalGuidance();
        InstanceAgentGuidanceRepository.Row row = new InstanceAgentGuidanceRepository.Row(
                instanceId,
                nextPrompt,
                nextEnabled,
                updatedBy,
                now
        );
        return buildResponse(instanceId, row, global);
    }

    public InstanceMainAgentGuidanceResponse deleteOverride(UUID instanceId) {
        requireInstance(instanceId);
        guidanceRepository.deleteByInstanceId(instanceId);
        ResolvedGlobalGuidance global = resolveGlobalGuidance();
        return buildResponse(instanceId, null, global);
    }

    public RuntimeGuidance resolveRuntimeGuidance(UUID instanceId) {
        requireInstance(instanceId);
        Optional<InstanceAgentGuidanceRepository.Row> overrideRow = guidanceRepository.findByInstanceId(instanceId);
        ResolvedGlobalGuidance global = resolveGlobalGuidance();
        InstanceMainAgentGuidanceResponse response = buildResponse(instanceId, overrideRow.orElse(null), global);
        return new RuntimeGuidance(
                response.effectivePrompt(),
                response.source(),
                response.workspacePath(),
                response.overwriteOnStart()
        );
    }

    private void requireInstance(UUID instanceId) {
        instanceRepository.findById(instanceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "instance not found"));
    }

    private InstanceMainAgentGuidanceResponse buildResponse(UUID instanceId,
                                                            InstanceAgentGuidanceRepository.Row overrideRow,
                                                            ResolvedGlobalGuidance global) {
        String source;
        String effectivePrompt;
        boolean overrideExists = overrideRow != null;

        if (overrideRow != null
                && overrideRow.enabled()
                && StringUtils.hasText(overrideRow.agentsMd())
                && isPromptWithinLimit(overrideRow.agentsMd(), "instance override")) {
            source = "INSTANCE_OVERRIDE";
            effectivePrompt = overrideRow.agentsMd();
        } else {
            source = global.source();
            effectivePrompt = global.content();
        }

        return new InstanceMainAgentGuidanceResponse(
                instanceId,
                safePath(properties.getInstanceWorkspacePath(), "/zeroclaw-data/workspace/AGENTS.md"),
                source,
                effectivePrompt,
                properties.isOverwriteOnStart(),
                overrideExists,
                overrideRow == null ? null : overrideRow.enabled(),
                overrideRow == null ? null : overrideRow.agentsMd(),
                safePath(properties.getDefaultMainAgentsMdPath(), ""),
                overrideRow == null ? null : overrideRow.updatedAt(),
                overrideRow == null ? null : overrideRow.updatedBy()
        );
    }

    private ResolvedGlobalGuidance resolveGlobalGuidance() {
        String configuredPath = safePath(properties.getDefaultMainAgentsMdPath(), "");
        if (StringUtils.hasText(configuredPath)) {
            try {
                String fromFile = Files.readString(Path.of(configuredPath), StandardCharsets.UTF_8);
                if (StringUtils.hasText(fromFile) && isPromptWithinLimit(fromFile, "global file")) {
                    return new ResolvedGlobalGuidance(fromFile, "GLOBAL_FILE");
                }
            } catch (IOException ex) {
                log.warn("failed to read global AGENTS.md from {}: {}", configuredPath, ex.getMessage());
            } catch (RuntimeException ex) {
                log.warn("invalid global AGENTS.md path {}: {}", configuredPath, ex.getMessage());
            }
        }
        if (StringUtils.hasText(properties.getDefaultMainAgentsMd())
                && isPromptWithinLimit(properties.getDefaultMainAgentsMd(), "global inline")) {
            return new ResolvedGlobalGuidance(properties.getDefaultMainAgentsMd(), "GLOBAL_INLINE");
        }
        return new ResolvedGlobalGuidance(null, "NONE");
    }

    private void validatePromptSize(String prompt, String source) {
        int sizeBytes = prompt.getBytes(StandardCharsets.UTF_8).length;
        int maxBytes = resolveMaxBytes();
        if (sizeBytes > maxBytes) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    source + " prompt exceeds size limit: " + sizeBytes + " bytes > " + maxBytes + " bytes");
        }
    }

    private boolean isPromptWithinLimit(String prompt, String source) {
        int sizeBytes = prompt.getBytes(StandardCharsets.UTF_8).length;
        int maxBytes = resolveMaxBytes();
        if (sizeBytes <= maxBytes) {
            return true;
        }
        log.warn("{} prompt ignored because size {} exceeds limit {}", source, sizeBytes, maxBytes);
        return false;
    }

    private int resolveMaxBytes() {
        return properties.getMaxBytes() > 0 ? properties.getMaxBytes() : 262144;
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

    private record ResolvedGlobalGuidance(String content, String source) {
    }

    public record RuntimeGuidance(
            String content,
            String source,
            String workspacePath,
            boolean overwriteOnStart
    ) {
    }
}
