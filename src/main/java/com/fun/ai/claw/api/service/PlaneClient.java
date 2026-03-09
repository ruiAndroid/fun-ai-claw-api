package com.fun.ai.claw.api.service;

import com.fun.ai.claw.api.model.AgentDescriptorResponse;
import com.fun.ai.claw.api.model.AgentSystemPromptResponse;
import com.fun.ai.claw.api.model.InstanceActionType;
import com.fun.ai.claw.api.model.SkillDescriptorResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class PlaneClient {
    private static final String AGENTS_MD_CONTENT_PAYLOAD_KEY = "agentsMdContent";
    private static final String AGENTS_MD_OVERWRITE_PAYLOAD_KEY = "agentsMdOverwrite";

    private final RestClient restClient;
    private final String planeBaseUrl;
    private final String requestedBy;
    private final InstanceMainAgentGuidanceService instanceMainAgentGuidanceService;

    public PlaneClient(@Value("${app.plane.base-url:http://127.0.0.1:8090/internal/v1}") String planeBaseUrl,
                       @Value("${app.plane.requested-by:fun-ai-claw-api}") String requestedBy,
                       @Value("${app.plane.connect-timeout-seconds:3}") long connectTimeoutSeconds,
                       @Value("${app.plane.request-timeout-seconds:90}") long requestTimeoutSeconds,
                       InstanceMainAgentGuidanceService instanceMainAgentGuidanceService) {
        long resolvedConnectTimeoutSeconds = connectTimeoutSeconds > 0 ? connectTimeoutSeconds : 3;
        long resolvedRequestTimeoutSeconds = requestTimeoutSeconds > 0 ? requestTimeoutSeconds : 90;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(resolvedConnectTimeoutSeconds))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(resolvedRequestTimeoutSeconds));
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .build();
        this.planeBaseUrl = planeBaseUrl;
        this.requestedBy = requestedBy;
        this.instanceMainAgentGuidanceService = instanceMainAgentGuidanceService;
    }

    public PlaneTaskExecutionRecord reconcileInstanceAction(UUID instanceId,
                                                            InstanceActionType action,
                                                            String image,
                                                            Integer gatewayHostPort) {
        Map<String, Object> payload = new HashMap<>();
        if (StringUtils.hasText(image)) {
            payload.put("image", image.trim());
        }
        if (gatewayHostPort != null) {
            payload.put("gatewayHostPort", gatewayHostPort);
        }
        if (action == InstanceActionType.START || action == InstanceActionType.RESTART || action == InstanceActionType.ROLLBACK) {
            InstanceMainAgentGuidanceService.RuntimeGuidance guidance =
                    instanceMainAgentGuidanceService.resolveRuntimeGuidance(instanceId);
            payload.put(AGENTS_MD_OVERWRITE_PAYLOAD_KEY, guidance.overwriteOnStart());
            if (guidance.content() != null) {
                payload.put(AGENTS_MD_CONTENT_PAYLOAD_KEY, guidance.content());
            } else if (guidance.overwriteOnStart()) {
                // Clear stale workspace AGENTS.md when there is no effective guidance.
                payload.put(AGENTS_MD_CONTENT_PAYLOAD_KEY, "");
            }
        }

        PlaneReconcileRequest request = new PlaneReconcileRequest(
                UUID.randomUUID(),
                instanceId,
                "INSTANCE_ACTION",
                action.name(),
                requestedBy,
                payload
        );

        try {
            PlaneTaskExecutionRecord record = restClient.post()
                    .uri(planeBaseUrl + "/reconcile")
                    .body(request)
                    .retrieve()
                    .body(PlaneTaskExecutionRecord.class);
            if (record == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "plane returned empty response");
            }
            return record;
        } catch (RestClientResponseException ex) {
            String details = ex.getResponseBodyAsString();
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "plane call failed: HTTP " + ex.getStatusCode().value() + (StringUtils.hasText(details) ? " " + details : "")
            );
        } catch (ResourceAccessException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "plane service unavailable: " + planeBaseUrl + "/reconcile"
                            + (StringUtils.hasText(ex.getMessage()) ? " (" + ex.getMessage() + ")" : "")
            );
        }
    }

    public void deleteInstance(UUID instanceId) {
        try {
            restClient.delete()
                    .uri(planeBaseUrl + "/instances/" + instanceId)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            String details = ex.getResponseBodyAsString();
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "plane delete failed: HTTP " + ex.getStatusCode().value() + (StringUtils.hasText(details) ? " " + details : "")
            );
        } catch (ResourceAccessException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "plane service unavailable: " + planeBaseUrl + "/instances/" + instanceId
                            + (StringUtils.hasText(ex.getMessage()) ? " (" + ex.getMessage() + ")" : "")
            );
        }
    }

    public List<AgentDescriptorResponse> listAgents(UUID instanceId) {
        try {
            PlaneAgentListResponse response = restClient.get()
                    .uri(planeBaseUrl + "/instances/" + instanceId + "/agents")
                    .retrieve()
                    .body(PlaneAgentListResponse.class);
            if (response == null || response.items() == null) {
                return List.of();
            }
            return response.items();
        } catch (RestClientResponseException ex) {
            throw mapPlaneQueryFailure("plane agent list failed", ex);
        } catch (ResourceAccessException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "plane service unavailable: " + planeBaseUrl + "/instances/" + instanceId + "/agents"
                            + (StringUtils.hasText(ex.getMessage()) ? " (" + ex.getMessage() + ")" : "")
            );
        }
    }

    public AgentSystemPromptResponse getAgentSystemPrompt(UUID instanceId, String agentId) {
        try {
            AgentSystemPromptResponse response = restClient.get()
                    .uri(planeBaseUrl + "/instances/{instanceId}/agents/{agentId}/system-prompt", instanceId, agentId)
                    .retrieve()
                    .body(AgentSystemPromptResponse.class);
            if (response == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "plane returned empty response");
            }
            return response;
        } catch (RestClientResponseException ex) {
            throw mapPlaneQueryFailure("plane agent prompt failed", ex);
        } catch (ResourceAccessException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "plane service unavailable: " + planeBaseUrl + "/instances/" + instanceId + "/agents/" + agentId + "/system-prompt"
                            + (StringUtils.hasText(ex.getMessage()) ? " (" + ex.getMessage() + ")" : "")
            );
        }
    }

    public List<SkillDescriptorResponse> listSkills(UUID instanceId) {
        try {
            PlaneSkillListResponse response = restClient.get()
                    .uri(planeBaseUrl + "/instances/" + instanceId + "/skills")
                    .retrieve()
                    .body(PlaneSkillListResponse.class);
            if (response == null || response.items() == null) {
                return List.of();
            }
            return response.items();
        } catch (RestClientResponseException ex) {
            throw mapPlaneQueryFailure("plane skill list failed", ex);
        } catch (ResourceAccessException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "plane service unavailable: " + planeBaseUrl + "/instances/" + instanceId + "/skills"
                            + (StringUtils.hasText(ex.getMessage()) ? " (" + ex.getMessage() + ")" : "")
            );
        }
    }

    private ResponseStatusException mapPlaneQueryFailure(String operation, RestClientResponseException ex) {
        String details = ex.getResponseBodyAsString();
        if (ex.getStatusCode().is4xxClientError()) {
            HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
            if (status != null) {
                throw new ResponseStatusException(
                        status,
                        StringUtils.hasText(details) ? details : operation + ": HTTP " + ex.getStatusCode().value()
                );
            }
        }
        throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                operation + ": HTTP " + ex.getStatusCode().value() + (StringUtils.hasText(details) ? " " + details : "")
        );
    }

    public record PlaneReconcileRequest(
            UUID taskId,
            UUID instanceId,
            String commandType,
            String action,
            String requestedBy,
            Map<String, Object> payload
    ) {
    }

    public record PlaneTaskExecutionRecord(
            UUID taskId,
            UUID instanceId,
            String commandType,
            String action,
            String status,
            String message,
            String executedAt
    ) {
        public boolean succeeded() {
            return "SUCCEEDED".equalsIgnoreCase(status);
        }
    }

    private record PlaneAgentListResponse(
            List<AgentDescriptorResponse> items
    ) {
    }

    private record PlaneSkillListResponse(
            List<SkillDescriptorResponse> items
    ) {
    }
}

