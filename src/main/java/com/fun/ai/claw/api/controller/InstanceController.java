package com.fun.ai.claw.api.controller;

import com.fun.ai.claw.api.model.AgentDescriptorResponse;
import com.fun.ai.claw.api.model.AgentSystemPromptResponse;
import com.fun.ai.claw.api.model.CreateInstanceRequest;
import com.fun.ai.claw.api.model.InstanceActionRequest;
import com.fun.ai.claw.api.model.InstanceAgentBindingResponse;
import com.fun.ai.claw.api.model.InstanceConfigResponse;
import com.fun.ai.claw.api.model.InstanceDefaultModelConfigResponse;
import com.fun.ai.claw.api.model.InstanceMainAgentGuidanceResponse;
import com.fun.ai.claw.api.model.InstanceRoutingConfigResponse;
import com.fun.ai.claw.api.model.InstanceSkillBindingResponse;
import com.fun.ai.claw.api.model.ListResponse;
import com.fun.ai.claw.api.model.ClawInstanceDto;
import com.fun.ai.claw.api.model.PairingCodeResponse;
import com.fun.ai.claw.api.model.SkillDescriptorResponse;
import com.fun.ai.claw.api.model.UpsertAgentSystemPromptRequest;
import com.fun.ai.claw.api.model.UpsertInstanceAgentBindingRequest;
import com.fun.ai.claw.api.model.UpsertInstanceConfigRequest;
import com.fun.ai.claw.api.model.UpsertInstanceDefaultModelConfigRequest;
import com.fun.ai.claw.api.model.UpsertInstanceMainAgentGuidanceRequest;
import com.fun.ai.claw.api.model.UpsertInstanceRoutingConfigRequest;
import com.fun.ai.claw.api.model.UpsertInstanceSkillBindingRequest;
import com.fun.ai.claw.api.service.InstanceAgentBindingService;
import com.fun.ai.claw.api.service.ControlService;
import com.fun.ai.claw.api.service.InstanceAgentPromptMutationService;
import com.fun.ai.claw.api.service.InstanceAgentService;
import com.fun.ai.claw.api.service.InstanceConfigMutationService;
import com.fun.ai.claw.api.service.InstanceConfigService;
import com.fun.ai.claw.api.service.InstanceDefaultModelConfigService;
import com.fun.ai.claw.api.service.InstanceMainAgentGuidanceService;
import com.fun.ai.claw.api.service.InstanceRoutingConfigService;
import com.fun.ai.claw.api.service.InstanceSkillBindingService;
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
    private final InstanceAgentPromptMutationService instanceAgentPromptMutationService;
    private final InstanceSkillService instanceSkillService;
    private final InstanceMainAgentGuidanceService instanceMainAgentGuidanceService;
    private final InstanceConfigService instanceConfigService;
    private final InstanceConfigMutationService instanceConfigMutationService;
    private final InstanceDefaultModelConfigService instanceDefaultModelConfigService;
    private final InstanceRoutingConfigService instanceRoutingConfigService;
    private final InstanceSkillBindingService instanceSkillBindingService;
    private final InstanceAgentBindingService instanceAgentBindingService;

    public InstanceController(ControlService controlService,
                              PairingCodeService pairingCodeService,
                              InstanceAgentService instanceAgentService,
                              InstanceAgentPromptMutationService instanceAgentPromptMutationService,
                              InstanceSkillService instanceSkillService,
                              InstanceMainAgentGuidanceService instanceMainAgentGuidanceService,
                              InstanceConfigService instanceConfigService,
                              InstanceConfigMutationService instanceConfigMutationService,
                              InstanceDefaultModelConfigService instanceDefaultModelConfigService,
                              InstanceRoutingConfigService instanceRoutingConfigService,
                              InstanceSkillBindingService instanceSkillBindingService,
                              InstanceAgentBindingService instanceAgentBindingService) {
        this.controlService = controlService;
        this.pairingCodeService = pairingCodeService;
        this.instanceAgentService = instanceAgentService;
        this.instanceAgentPromptMutationService = instanceAgentPromptMutationService;
        this.instanceSkillService = instanceSkillService;
        this.instanceMainAgentGuidanceService = instanceMainAgentGuidanceService;
        this.instanceConfigService = instanceConfigService;
        this.instanceConfigMutationService = instanceConfigMutationService;
        this.instanceDefaultModelConfigService = instanceDefaultModelConfigService;
        this.instanceRoutingConfigService = instanceRoutingConfigService;
        this.instanceSkillBindingService = instanceSkillBindingService;
        this.instanceAgentBindingService = instanceAgentBindingService;
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
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void submitAction(@PathVariable UUID instanceId,
                             @Valid @RequestBody InstanceActionRequest request) {
        controlService.submitInstanceAction(instanceId, request);
    }

    @GetMapping("/{instanceId}/pairing-code")
    public PairingCodeResponse getPairingCode(@PathVariable UUID instanceId) {
        return pairingCodeService.fetchPairingCode(instanceId);
    }

    @GetMapping("/{instanceId}/agents")
    public ListResponse<AgentDescriptorResponse> listAgents(@PathVariable UUID instanceId) {
        return new ListResponse<>(instanceAgentService.listAgents(instanceId));
    }

    @GetMapping("/{instanceId}/agents/{agentId}/system-prompt")
    public AgentSystemPromptResponse getAgentSystemPrompt(@PathVariable UUID instanceId,
                                                          @PathVariable String agentId) {
        return instanceAgentService.getAgentSystemPrompt(instanceId, agentId);
    }

    @PutMapping("/{instanceId}/agents/{agentId}/system-prompt")
    public AgentSystemPromptResponse upsertAgentSystemPrompt(@PathVariable UUID instanceId,
                                                             @PathVariable String agentId,
                                                             @RequestBody UpsertAgentSystemPromptRequest request) {
        return instanceAgentPromptMutationService.upsertSystemPrompt(instanceId, agentId, request);
    }

    @GetMapping("/{instanceId}/agent-bindings")
    public ListResponse<InstanceAgentBindingResponse> listAgentBindings(@PathVariable UUID instanceId) {
        return instanceAgentBindingService.listBindings(instanceId);
    }

    @PutMapping("/{instanceId}/agent-bindings/{agentKey}")
    public InstanceAgentBindingResponse upsertAgentBinding(@PathVariable UUID instanceId,
                                                           @PathVariable String agentKey,
                                                           @RequestBody(required = false) UpsertInstanceAgentBindingRequest request) {
        return instanceAgentBindingService.upsert(instanceId, agentKey, request);
    }

    @DeleteMapping("/{instanceId}/agent-bindings/{agentKey}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void uninstallAgentBinding(@PathVariable UUID instanceId,
                                      @PathVariable String agentKey) {
        instanceAgentBindingService.uninstall(instanceId, agentKey);
    }

    @GetMapping("/{instanceId}/skills")
    public ListResponse<SkillDescriptorResponse> listSkills(@PathVariable UUID instanceId) {
        return new ListResponse<>(instanceSkillService.listSkills(instanceId));
    }

    @GetMapping("/{instanceId}/skill-bindings")
    public ListResponse<InstanceSkillBindingResponse> listSkillBindings(@PathVariable UUID instanceId) {
        return instanceSkillBindingService.listBindings(instanceId);
    }

    @PutMapping("/{instanceId}/skill-bindings/{skillKey}")
    public InstanceSkillBindingResponse installSkillBinding(@PathVariable UUID instanceId,
                                                            @PathVariable String skillKey,
                                                            @RequestBody(required = false) UpsertInstanceSkillBindingRequest request) {
        return instanceSkillBindingService.install(instanceId, skillKey, request);
    }

    @DeleteMapping("/{instanceId}/skill-bindings/{skillKey}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void uninstallSkillBinding(@PathVariable UUID instanceId,
                                      @PathVariable String skillKey) {
        instanceSkillBindingService.uninstall(instanceId, skillKey);
    }

    @GetMapping("/{instanceId}/config")
    public InstanceConfigResponse getInstanceConfig(@PathVariable UUID instanceId) {
        return instanceConfigService.get(instanceId);
    }

    @PutMapping("/{instanceId}/config")
    public InstanceConfigResponse upsertInstanceConfig(@PathVariable UUID instanceId,
                                                       @RequestBody UpsertInstanceConfigRequest request) {
        return instanceConfigMutationService.upsert(instanceId, request);
    }

    @DeleteMapping("/{instanceId}/config")
    public InstanceConfigResponse deleteInstanceConfigOverride(@PathVariable UUID instanceId) {
        return instanceConfigMutationService.deleteOverride(instanceId);
    }

    @GetMapping("/{instanceId}/default-model-config")
    public InstanceDefaultModelConfigResponse getInstanceDefaultModelConfig(@PathVariable UUID instanceId) {
        return instanceDefaultModelConfigService.get(instanceId);
    }

    @PutMapping("/{instanceId}/default-model-config")
    public InstanceDefaultModelConfigResponse upsertInstanceDefaultModelConfig(@PathVariable UUID instanceId,
                                                                               @RequestBody UpsertInstanceDefaultModelConfigRequest request) {
        return instanceDefaultModelConfigService.upsert(instanceId, request);
    }

    @GetMapping("/{instanceId}/routing-config")
    public InstanceRoutingConfigResponse getInstanceRoutingConfig(@PathVariable UUID instanceId) {
        return instanceRoutingConfigService.get(instanceId);
    }

    @PutMapping("/{instanceId}/routing-config")
    public InstanceRoutingConfigResponse upsertInstanceRoutingConfig(@PathVariable UUID instanceId,
                                                                    @RequestBody UpsertInstanceRoutingConfigRequest request) {
        return instanceRoutingConfigService.upsert(instanceId, request);
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
