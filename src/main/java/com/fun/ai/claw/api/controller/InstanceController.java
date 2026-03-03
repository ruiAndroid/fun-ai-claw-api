package com.fun.ai.claw.api.controller;

import com.fun.ai.claw.api.model.AcceptedActionResponse;
import com.fun.ai.claw.api.model.CreateInstanceRequest;
import com.fun.ai.claw.api.model.InstanceActionRequest;
import com.fun.ai.claw.api.model.ListResponse;
import com.fun.ai.claw.api.model.ClawInstanceDto;
import com.fun.ai.claw.api.model.PairingCodeResponse;
import com.fun.ai.claw.api.service.ControlService;
import com.fun.ai.claw.api.service.PairingCodeService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    public InstanceController(ControlService controlService, PairingCodeService pairingCodeService) {
        this.controlService = controlService;
        this.pairingCodeService = pairingCodeService;
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

    @DeleteMapping("/{instanceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteInstance(@PathVariable UUID instanceId) {
        controlService.deleteInstance(instanceId);
    }
}

