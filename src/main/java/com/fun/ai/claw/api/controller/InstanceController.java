package com.fun.ai.claw.api.controller;

import com.fun.ai.claw.api.model.AcceptedActionResponse;
import com.fun.ai.claw.api.model.AgentDescriptorResponse;
import com.fun.ai.claw.api.model.CreateInstanceRequest;
import com.fun.ai.claw.api.model.InstanceActionRequest;
import com.fun.ai.claw.api.model.InstanceMainAgentGuidanceResponse;
import com.fun.ai.claw.api.model.ListResponse;
import com.fun.ai.claw.api.model.ClawInstanceDto;
import com.fun.ai.claw.api.model.PairingCodeResponse;
import com.fun.ai.claw.api.model.SkillDescriptorResponse;
import com.fun.ai.claw.api.model.UpsertInstanceMainAgentGuidanceRequest;
import com.fun.ai.claw.api.service.ControlService;
import com.fun.ai.claw.api.service.InstanceAgentService;
import com.fun.ai.claw.api.service.InstanceMainAgentGuidanceService;
import com.fun.ai.claw.api.service.InstanceSkillService;
import com.fun.ai.claw.api.service.PairingCodeService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/instances")
public class InstanceController {

    private final ControlService controlService;
    private final PairingCodeService pairingCodeService;
    private final InstanceAgentService instanceAgentService;
    private final InstanceSkillService instanceSkillService;
    private final InstanceMainAgentGuidanceService instanceMainAgentGuidanceService;

    public InstanceController(ControlService controlService,
                              PairingCodeService pairingCodeService,
                              InstanceAgentService instanceAgentService,
                              InstanceSkillService instanceSkillService,
                              InstanceMainAgentGuidanceService instanceMainAgentGuidanceService) {
        this.controlService = controlService;
        this.pairingCodeService = pairingCodeService;
        this.instanceAgentService = instanceAgentService;
        this.instanceSkillService = instanceSkillService;
        this.instanceMainAgentGuidanceService = instanceMainAgentGuidanceService;
    }

    @GetMapping
    public ListResponse<ClawInstanceDto> listInstances() {
        return new ListResponse<>(controlService.listInstances());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ClawInstanceDto createInstance(@Valid @RequestBody CreateInstanceRequest request) {
        return controlService.createInstance(request);
    }

    @PostMapping("/{instanceId}/actions")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AcceptedActionResponse submitAction(@PathVariable UUID instanceId,
                                               @Valid @RequestBody InstanceActionRequest request) {
        return controlService.submitInstanceAction(instanceId, request);
    }

    @GetMapping("/{instanceId}/pairing-code")
    public PairingCodeResponse getPairingCode(@PathVariable UUID instanceId) {
        return pairingCodeService.fetchPairingCode(instanceId);
    }

    @GetMapping("/{instanceId}/agents")
    public ListResponse<AgentDescriptorResponse> listAgents(@PathVariable UUID instanceId) {
        return new ListResponse<>(instanceAgentService.listAgents(instanceId));
    }

    @GetMapping("/{instanceId}/skills")
    public ListResponse<SkillDescriptorResponse> listSkills(@PathVariable UUID instanceId) {
        return new ListResponse<>(instanceSkillService.listSkills(instanceId));
    }

    @GetMapping("/{instanceId}/main-agent-guidance")
    public InstanceMainAgentGuidanceResponse getMainAgentGuidance(@PathVariable UUID instanceId) {
        return instanceMainAgentGuidanceService.get(instanceId);
    }

    @PutMapping("/{instanceId}/main-agent-guidance")
    public InstanceMainAgentGuidanceResponse upsertMainAgentGuidance(@PathVariable UUID instanceId,
                                                                     @RequestBody UpsertInstanceMainAgentGuidanceRequest request) {
        return instanceMainAgentGuidanceService.upsert(instanceId, request);
    }

    @DeleteMapping("/{instanceId}/main-agent-guidance")
    public InstanceMainAgentGuidanceResponse deleteMainAgentGuidance(@PathVariable UUID instanceId) {
        return instanceMainAgentGuidanceService.deleteOverride(instanceId);
    }

    @DeleteMapping("/{instanceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteInstance(@PathVariable UUID instanceId) {
        controlService.deleteInstance(instanceId);
    }
}

