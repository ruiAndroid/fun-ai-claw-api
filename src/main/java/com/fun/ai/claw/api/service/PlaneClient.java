package com.fun.ai.claw.api.service;

import com.fun.ai.claw.api.model.AgentDescriptorResponse;
import com.fun.ai.claw.api.model.AgentSystemPromptResponse;
import com.fun.ai.claw.api.model.InstanceActionType;
import com.fun.ai.claw.api.model.ManagedSkillAssetPayload;
import com.fun.ai.claw.api.model.PairingCodeResponse;
import com.fun.ai.claw.api.model.SkillDescriptorResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.server.ResponseStatusException;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class PlaneClient {
    private static final String AGENTS_MD_CONTENT_PAYLOAD_KEY = "agentsMdContent";
    private static final String AGENTS_MD_OVERWRITE_PAYLOAD_KEY = "agentsMdOverwrite";
    private static final String CONFIG_TOML_CONTENT_PAYLOAD_KEY = "configTomlContent";
    private static final String CONFIG_TOML_OVERWRITE_PAYLOAD_KEY = "configTomlOverwrite";
    private static final String MANAGED_SKILLS_PAYLOAD_KEY = "managedSkills";

    private final RestClient restClient;
    private final String planeBaseUrl;
    private final String requestedBy;
    private final InstanceMainAgentGuidanceService instanceMainAgentGuidanceService;
    private final InstanceConfigService instanceConfigService;
    private final ManagedSkillAssetService managedSkillAssetService;

    public PlaneClient(@Value("${app.plane.base-url:http://127.0.0.1:8090/internal/v1}") String planeBaseUrl,
                       @Value("${app.plane.requested-by:fun-ai-claw-api}") String requestedBy,
                        @Value("${app.plane.connect-timeout-seconds:3}") long connectTimeoutSeconds,
                        @Value("${app.plane.request-timeout-seconds:90}") long requestTimeoutSeconds,
                        InstanceMainAgentGuidanceService instanceMainAgentGuidanceService,
                        InstanceConfigService instanceConfigService,
                        ManagedSkillAssetService managedSkillAssetService) {
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
        this.instanceConfigService = instanceConfigService;
        this.managedSkillAssetService = managedSkillAssetService;
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
            InstanceConfigService.RuntimeConfig runtimeConfig = instanceConfigService.resolveRuntimeConfig(instanceId);
            payload.put(CONFIG_TOML_OVERWRITE_PAYLOAD_KEY, runtimeConfig.overwriteOnStart());
            if (runtimeConfig.content() != null) {
                payload.put(CONFIG_TOML_CONTENT_PAYLOAD_KEY, runtimeConfig.content());
            } else if (runtimeConfig.overwriteOnStart()) {
                payload.put(CONFIG_TOML_CONTENT_PAYLOAD_KEY, "");
            }
            payload.put(MANAGED_SKILLS_PAYLOAD_KEY, managedSkillAssetService.listEnabledByInstanceId(instanceId));
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

    public PairingCodeResponse getPairingCode(UUID instanceId) {
        try {
            PairingCodeResponse response = restClient.get()
                    .uri(planeBaseUrl + "/instances/{instanceId}/pairing-code", instanceId)
                    .retrieve()
                    .body(PairingCodeResponse.class);
            if (response == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "plane returned empty response");
            }
            return response;
        } catch (RestClientResponseException ex) {
            throw mapPlaneQueryFailure("plane pairing code failed", ex);
        } catch (ResourceAccessException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "plane service unavailable: " + planeBaseUrl + "/instances/" + instanceId + "/pairing-code"
                            + (StringUtils.hasText(ex.getMessage()) ? " (" + ex.getMessage() + ")" : "")
            );
        }
    }

    public String readRuntimeFileText(UUID instanceId, String path) {
        try {
            byte[] response = restClient.get()
                    .uri(buildRuntimeFileUri(instanceId, path, null))
                    .retrieve()
                    .body(byte[].class);
            if (response == null) {
                return null;
            }
            return new String(response, StandardCharsets.UTF_8);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) {
                return null;
            }
            throw mapPlaneQueryFailure("plane runtime file read failed", ex);
        } catch (ResourceAccessException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "plane service unavailable: " + buildRuntimeFileUri(instanceId, path, null)
                            + (StringUtils.hasText(ex.getMessage()) ? " (" + ex.getMessage() + ")" : "")
            );
        }
    }

    public void writeRuntimeFile(UUID instanceId, String path, byte[] content, boolean overwrite) {
        try {
            restClient.put()
                    .uri(buildRuntimeFileUri(instanceId, path, overwrite))
                    .body(content == null ? new byte[0] : content)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            throw mapPlaneQueryFailure("plane runtime file write failed", ex);
        } catch (ResourceAccessException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "plane service unavailable: " + buildRuntimeFileUri(instanceId, path, overwrite)
                            + (StringUtils.hasText(ex.getMessage()) ? " (" + ex.getMessage() + ")" : "")
            );
        }
    }

    public void syncInstanceSkills(UUID instanceId) {
        try {
            restClient.post()
                    .uri(planeBaseUrl + "/instances/" + instanceId + "/skills/sync")
                    .body(new PlaneSkillSyncRequest(managedSkillAssetService.listEnabledByInstanceId(instanceId)))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            throw mapPlaneQueryFailure("plane instance skill sync failed", ex);
        } catch (ResourceAccessException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "plane service unavailable: " + planeBaseUrl + "/instances/" + instanceId + "/skills/sync"
                            + (StringUtils.hasText(ex.getMessage()) ? " (" + ex.getMessage() + ")" : "")
            );
        }
    }

    public void uploadSkillPackage(String skillKey, byte[] zipBytes, boolean overwrite) {
        try {
            restClient.put()
                    .uri(UriComponentsBuilder.fromUriString(planeBaseUrl)
                            .path("/skill-packages/{skillKey}")
                            .queryParam("overwrite", overwrite)
                            .buildAndExpand(skillKey)
                            .toUriString())
                    .body(zipBytes == null ? new byte[0] : zipBytes)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            throw mapPlaneQueryFailure("plane skill package upload failed", ex);
        } catch (ResourceAccessException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "plane service unavailable: " + planeBaseUrl + "/skill-packages/" + skillKey
                            + (StringUtils.hasText(ex.getMessage()) ? " (" + ex.getMessage() + ")" : "")
            );
        }
    }

    public void deleteSkillPackage(String skillKey) {
        try {
            restClient.delete()
                    .uri(planeBaseUrl + "/skill-packages/{skillKey}", skillKey)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) {
                return;
            }
            throw mapPlaneQueryFailure("plane skill package delete failed", ex);
        } catch (ResourceAccessException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "plane service unavailable: " + planeBaseUrl + "/skill-packages/" + skillKey
                            + (StringUtils.hasText(ex.getMessage()) ? " (" + ex.getMessage() + ")" : "")
            );
        }
    }

    private String buildRuntimeFileUri(UUID instanceId, String path, Boolean overwrite) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(planeBaseUrl)
                .path("/instances/{instanceId}/files")
                .queryParam("path", path);
        if (overwrite != null) {
            builder.queryParam("overwrite", overwrite);
        }
        return builder.buildAndExpand(instanceId).toUriString();
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

    private record PlaneSkillSyncRequest(
            List<ManagedSkillAssetPayload> items
    ) {
    }
}

