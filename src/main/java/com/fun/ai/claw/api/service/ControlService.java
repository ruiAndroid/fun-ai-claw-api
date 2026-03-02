package com.fun.ai.claw.api.service;

import com.fun.ai.claw.api.config.ImageCatalogProperties;
import com.fun.ai.claw.api.model.AcceptedActionResponse;
import com.fun.ai.claw.api.model.ClawInstanceDto;
import com.fun.ai.claw.api.model.CreateInstanceRequest;
import com.fun.ai.claw.api.model.ImagePresetDto;
import com.fun.ai.claw.api.model.InstanceActionRequest;
import com.fun.ai.claw.api.model.InstanceActionType;
import com.fun.ai.claw.api.model.InstanceDesiredState;
import com.fun.ai.claw.api.model.InstanceRuntime;
import com.fun.ai.claw.api.model.InstanceStatus;
import com.fun.ai.claw.api.repository.InstanceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class ControlService {

    private final InstanceRepository instanceRepository;
    private final ImageCatalogProperties imageCatalogProperties;
    private final PlaneClient planeClient;
    private final int gatewayPortRangeStart;
    private final int gatewayPortRangeEnd;
    private final String gatewayUrlTemplate;
    private final String remoteConnectCommandTemplate;

    public ControlService(InstanceRepository instanceRepository,
                          ImageCatalogProperties imageCatalogProperties,
                          PlaneClient planeClient,
                          @Value("${app.gateway.port-range-start:42617}") int gatewayPortRangeStart,
                          @Value("${app.gateway.port-range-end:42717}") int gatewayPortRangeEnd,
                          @Value("${app.gateway.url-template:http://172.21.138.98:{port}}") String gatewayUrlTemplate,
                          @Value("${app.remote-connect.command-template:}") String remoteConnectCommandTemplate) {
        this.instanceRepository = instanceRepository;
        this.imageCatalogProperties = imageCatalogProperties;
        this.planeClient = planeClient;
        this.gatewayPortRangeStart = gatewayPortRangeStart;
        this.gatewayPortRangeEnd = gatewayPortRangeEnd;
        this.gatewayUrlTemplate = gatewayUrlTemplate;
        this.remoteConnectCommandTemplate = remoteConnectCommandTemplate;
    }

    public List<ClawInstanceDto> listInstances() {
        return instanceRepository.findAll().stream()
                .map(this::attachGatewayUrl)
                .toList();
    }

    public List<ImagePresetDto> listImagePresets() {
        return imageCatalogProperties.getPresets().stream()
                .filter(this::isValidPreset)
                .map(preset -> new ImagePresetDto(
                        preset.getId().trim(),
                        preset.getName().trim(),
                        preset.getImage().trim(),
                        InstanceRuntime.ZEROCLAW,
                        preset.getDescription(),
                        preset.isRecommended()
                ))
                .toList();
    }

    @Transactional
    public ClawInstanceDto createInstance(CreateInstanceRequest request) {
        String name = request.name().trim();
        validateInstanceName(name);

        String image = request.image().trim();
        validateRequestedImage(image);

        UUID hostId;
        try {
            hostId = UUID.fromString(request.hostId().trim());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "hostId must be a valid UUID");
        }

        UUID instanceId = UUID.randomUUID();
        Instant now = Instant.now();
        InstanceDesiredState desiredState = Objects.requireNonNullElse(request.desiredState(), InstanceDesiredState.RUNNING);
        InstanceStatus status = desiredState == InstanceDesiredState.RUNNING ? InstanceStatus.CREATING : InstanceStatus.STOPPED;
        int gatewayHostPort = allocateGatewayPort(hostId);

        ClawInstanceDto instance = new ClawInstanceDto(
                instanceId,
                name,
                hostId,
                image,
                gatewayHostPort,
                resolveGatewayUrl(instanceId, gatewayHostPort),
                resolveRemoteConnectCommand(instanceId, gatewayHostPort),
                InstanceRuntime.ZEROCLAW,
                status,
                desiredState,
                now,
                now
        );
        try {
            instanceRepository.insert(instance);
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "gateway host port already allocated");
        }

        if (desiredState == InstanceDesiredState.RUNNING) {
            PlaneExecutionResult executionResult = executeActionWithGatewayPortRetry(
                    instance.id(),
                    instance.hostId(),
                    InstanceActionType.START,
                    instance.image(),
                    instance.gatewayHostPort(),
                    now
            );
            PlaneClient.PlaneTaskExecutionRecord execution = executionResult.execution();
            if (!execution.succeeded()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "plane execution failed: " + execution.message());
            }
            Instant updatedAt = Instant.now();
            InstanceStatus finalStatus = InstanceStatus.RUNNING;
            instanceRepository.updateState(instance.id(), finalStatus, desiredState, updatedAt);
            return new ClawInstanceDto(
                    instance.id(),
                    instance.name(),
                    instance.hostId(),
                    instance.image(),
                    executionResult.gatewayHostPort(),
                    resolveGatewayUrl(instance.id(), executionResult.gatewayHostPort()),
                    resolveRemoteConnectCommand(instance.id(), executionResult.gatewayHostPort()),
                    instance.runtime(),
                    finalStatus,
                    desiredState,
                    instance.createdAt(),
                    updatedAt
            );
        }

        return attachGatewayUrl(instance);
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public AcceptedActionResponse submitInstanceAction(UUID instanceId, InstanceActionRequest request) {
        ClawInstanceDto instance = getInstance(instanceId);
        Instant now = Instant.now();

        InstanceDesiredState desiredState = desiredStateForAction(request.action());
        PlaneClient.PlaneTaskExecutionRecord execution;
        try {
            execution = executeActionWithGatewayPortRetry(
                    instance.id(),
                    instance.hostId(),
                    request.action(),
                    instance.image(),
                    instance.gatewayHostPort(),
                    now
            ).execution();
        } catch (ResponseStatusException ex) {
            instanceRepository.updateState(instance.id(), InstanceStatus.ERROR, desiredState, now);
            instanceRepository.insertAction(
                    instance.id(),
                    request.action(),
                    failureReason(request.reason(), ex.getReason()),
                    now
            );
            throw ex;
        }
        InstanceStatus status = execution.succeeded()
                ? statusForSuccessfulAction(request.action())
                : InstanceStatus.ERROR;

        instanceRepository.updateState(instance.id(), status, desiredState, now);
        UUID actionTaskId = instanceRepository.insertAction(
                instance.id(),
                request.action(),
                execution.succeeded() ? request.reason() : failureReason(request.reason(), execution.message()),
                now
        );
        if (!execution.succeeded()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "plane execution failed: " + execution.message());
        }
        return new AcceptedActionResponse(actionTaskId, now);
    }

    @Transactional
    public void deleteInstance(UUID instanceId) {
        getInstance(instanceId);
        planeClient.deleteInstance(instanceId);
        int deletedRows = instanceRepository.deleteById(instanceId);
        if (deletedRows == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "instance not found");
        }
    }

    private ClawInstanceDto getInstance(UUID instanceId) {
        return instanceRepository.findById(instanceId)
                .map(this::attachGatewayUrl)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "instance not found"));
    }

    private void validateInstanceName(String name) {
        if (!StringUtils.hasText(name)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name must not be blank");
        }

        if (instanceRepository.existsByNameIgnoreCase(name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "instance name already exists");
        }
    }

    private void validateRequestedImage(String image) {
        if (imageCatalogProperties.isAllowCustomImage()) {
            return;
        }

        List<ImageCatalogProperties.Preset> validPresets = imageCatalogProperties.getPresets().stream()
                .filter(this::isValidPreset)
                .toList();
        if (validPresets.isEmpty()) {
            return;
        }

        boolean matched = validPresets.stream()
                .anyMatch(preset -> image.equals(preset.getImage().trim()));
        if (!matched) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "image must come from configured presets");
        }
    }

    private boolean isValidPreset(ImageCatalogProperties.Preset preset) {
        return StringUtils.hasText(preset.getId())
                && StringUtils.hasText(preset.getName())
                && StringUtils.hasText(preset.getImage());
    }

    private InstanceDesiredState desiredStateForAction(InstanceActionType action) {
        return switch (action) {
            case STOP -> InstanceDesiredState.STOPPED;
            case START, RESTART, ROLLBACK -> InstanceDesiredState.RUNNING;
        };
    }

    private InstanceStatus statusForSuccessfulAction(InstanceActionType action) {
        return switch (action) {
            case STOP -> InstanceStatus.STOPPED;
            case START, RESTART, ROLLBACK -> InstanceStatus.RUNNING;
        };
    }

    private String failureReason(String reason, String executionMessage) {
        String fallback = StringUtils.hasText(executionMessage) ? executionMessage : "plane execution failed";
        if (!StringUtils.hasText(reason)) {
            return "[FAILED] " + fallback;
        }
        return reason + " | [FAILED] " + fallback;
    }

    private int allocateGatewayPort(UUID hostId) {
        Integer availablePort = findNextAvailableGatewayPort(hostId, Set.of());
        if (availablePort != null) {
            return availablePort;
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "no available gateway host ports");
    }

    private Integer findNextAvailableGatewayPort(UUID hostId, Set<Integer> excludedPorts) {
        if (gatewayPortRangeStart <= 0
                || gatewayPortRangeEnd <= 0
                || gatewayPortRangeStart > gatewayPortRangeEnd
                || gatewayPortRangeEnd > 65535) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "invalid gateway port range configuration");
        }

        Set<Integer> allocated = new HashSet<>(instanceRepository.findAllocatedGatewayPortsByHostId(hostId));
        allocated.addAll(excludedPorts);
        for (int port = gatewayPortRangeStart; port <= gatewayPortRangeEnd; port++) {
            if (!allocated.contains(port)) {
                return port;
            }
        }
        return null;
    }

    private PlaneExecutionResult executeActionWithGatewayPortRetry(UUID instanceId,
                                                                   UUID hostId,
                                                                   InstanceActionType action,
                                                                   String image,
                                                                   Integer initialGatewayHostPort,
                                                                   Instant updatedAt) {
        if (!actionRequiresGatewayPort(action)) {
            PlaneClient.PlaneTaskExecutionRecord execution = planeClient.reconcileInstanceAction(
                    instanceId,
                    action,
                    image,
                    initialGatewayHostPort
            );
            return new PlaneExecutionResult(execution, initialGatewayHostPort);
        }

        Set<Integer> attemptedPorts = new HashSet<>();
        Integer gatewayHostPort = initialGatewayHostPort;
        if (gatewayHostPort == null) {
            gatewayHostPort = assignNextGatewayHostPort(instanceId, hostId, updatedAt, attemptedPorts);
            if (gatewayHostPort == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "no available gateway host ports");
            }
        }

        int remainingRetries = Math.max(0, gatewayPortRangeEnd - gatewayPortRangeStart);
        while (true) {
            attemptedPorts.add(gatewayHostPort);
            PlaneClient.PlaneTaskExecutionRecord execution = planeClient.reconcileInstanceAction(
                    instanceId,
                    action,
                    image,
                    gatewayHostPort
            );
            if (execution.succeeded() || !isGatewayPortConflictMessage(execution.message()) || remainingRetries == 0) {
                return new PlaneExecutionResult(execution, gatewayHostPort);
            }

            cleanupFailedInstanceContainer(instanceId);
            Integer nextPort = assignNextGatewayHostPort(instanceId, hostId, Instant.now(), attemptedPorts);
            if (nextPort == null) {
                return new PlaneExecutionResult(execution, gatewayHostPort);
            }
            gatewayHostPort = nextPort;
            remainingRetries--;
        }
    }

    private Integer assignNextGatewayHostPort(UUID instanceId,
                                              UUID hostId,
                                              Instant updatedAt,
                                              Set<Integer> attemptedPorts) {
        while (true) {
            Integer nextPort = findNextAvailableGatewayPort(hostId, attemptedPorts);
            if (nextPort == null) {
                return null;
            }
            attemptedPorts.add(nextPort);
            try {
                instanceRepository.updateGatewayHostPort(instanceId, nextPort, updatedAt);
                return nextPort;
            } catch (DataIntegrityViolationException ex) {
                // Port may be taken by concurrent transaction, continue with the next candidate.
            }
        }
    }

    private boolean actionRequiresGatewayPort(InstanceActionType action) {
        return action == InstanceActionType.START
                || action == InstanceActionType.RESTART
                || action == InstanceActionType.ROLLBACK;
    }

    private boolean isGatewayPortConflictMessage(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        String normalized = message.toLowerCase();
        return normalized.contains("port is already allocated")
                || normalized.contains("address already in use")
                || (normalized.contains("bind") && normalized.contains("failed"));
    }

    private void cleanupFailedInstanceContainer(UUID instanceId) {
        try {
            planeClient.deleteInstance(instanceId);
        } catch (ResponseStatusException ex) {
            // Best effort cleanup; creation flow will return the original failure if retry still fails.
        }
    }

    private ClawInstanceDto attachGatewayUrl(ClawInstanceDto instance) {
        String gatewayUrl = resolveGatewayUrl(instance.id(), instance.gatewayHostPort());
        String remoteConnectCommand = resolveRemoteConnectCommand(instance.id(), instance.gatewayHostPort());
        return new ClawInstanceDto(
                instance.id(),
                instance.name(),
                instance.hostId(),
                instance.image(),
                instance.gatewayHostPort(),
                gatewayUrl,
                remoteConnectCommand,
                instance.runtime(),
                instance.status(),
                instance.desiredState(),
                instance.createdAt(),
                instance.updatedAt()
        );
    }

    private String resolveGatewayUrl(UUID instanceId, Integer gatewayHostPort) {
        if (gatewayHostPort == null || !StringUtils.hasText(gatewayUrlTemplate)) {
            return null;
        }
        return gatewayUrlTemplate
                .replace("{port}", String.valueOf(gatewayHostPort))
                .replace("{instanceId}", instanceId.toString());
    }

    private String resolveRemoteConnectCommand(UUID instanceId, Integer gatewayHostPort) {
        if (!StringUtils.hasText(remoteConnectCommandTemplate)) {
            return null;
        }
        String gatewayUrl = resolveGatewayUrl(instanceId, gatewayHostPort);
        String portText = gatewayHostPort == null ? "" : String.valueOf(gatewayHostPort);
        String gatewayUrlText = gatewayUrl == null ? "" : gatewayUrl;
        return remoteConnectCommandTemplate
                .replace("{instanceId}", instanceId.toString())
                .replace("{gatewayPort}", portText)
                .replace("{gatewayUrl}", gatewayUrlText);
    }

    private record PlaneExecutionResult(PlaneClient.PlaneTaskExecutionRecord execution, Integer gatewayHostPort) {
    }
}

