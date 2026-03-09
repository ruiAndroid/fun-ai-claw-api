package com.fun.ai.claw.api.service;

import com.fun.ai.claw.api.model.PairingCodeResponse;
import com.fun.ai.claw.api.repository.InstanceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class PairingCodeService {

    private final InstanceRepository instanceRepository;
    private final PlaneClient planeClient;

    public PairingCodeService(InstanceRepository instanceRepository,
                              PlaneClient planeClient) {
        this.instanceRepository = instanceRepository;
        this.planeClient = planeClient;
    }

    public PairingCodeResponse fetchPairingCode(UUID instanceId) {
        requireInstanceExists(instanceId);
        return planeClient.getPairingCode(instanceId);
    }

    private void requireInstanceExists(UUID instanceId) {
        instanceRepository.findById(instanceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "instance not found"));
    }
}
