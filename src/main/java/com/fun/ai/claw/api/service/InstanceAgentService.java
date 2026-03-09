package com.fun.ai.claw.api.service;

import com.fun.ai.claw.api.model.AgentDescriptorResponse;
import com.fun.ai.claw.api.model.AgentSystemPromptResponse;
import com.fun.ai.claw.api.repository.InstanceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
public class InstanceAgentService {

    private final InstanceRepository instanceRepository;
    private final PlaneClient planeClient;

    public InstanceAgentService(InstanceRepository instanceRepository,
                                PlaneClient planeClient) {
        this.instanceRepository = instanceRepository;
        this.planeClient = planeClient;
    }

    public List<AgentDescriptorResponse> listAgents(UUID instanceId) {
        requireInstanceExists(instanceId);
        return planeClient.listAgents(instanceId);
    }

    public AgentSystemPromptResponse getAgentSystemPrompt(UUID instanceId, String agentId) {
        requireInstanceExists(instanceId);
        return planeClient.getAgentSystemPrompt(instanceId, agentId);
    }

    private void requireInstanceExists(UUID instanceId) {
        instanceRepository.findById(instanceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "instance not found"));
    }
}
