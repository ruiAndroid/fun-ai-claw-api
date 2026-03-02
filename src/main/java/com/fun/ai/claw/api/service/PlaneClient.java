package com.fun.ai.claw.api.service;

import com.fun.ai.claw.api.model.InstanceActionType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class PlaneClient {

    private final RestClient restClient;
    private final String planeBaseUrl;
    private final String requestedBy;

    public PlaneClient(@Value("${app.plane.base-url:http://127.0.0.1:8090/internal/v1}") String planeBaseUrl,
                       @Value("${app.plane.requested-by:fun-ai-claw-api}") String requestedBy) {
        this.restClient = RestClient.create();
        this.planeBaseUrl = planeBaseUrl;
        this.requestedBy = requestedBy;
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
        }
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
}

