package com.fun.ai.claw.api.service;

import com.fun.ai.claw.api.model.ClawInstanceDto;
import com.fun.ai.claw.api.model.PairingCodeResponse;
import com.fun.ai.claw.api.repository.InstanceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

@Service
public class PairingCodeService {

    private final InstanceRepository instanceRepository;
    private final String fixedPairingCode;
    private final String fixedLinkPath;
    private final String gatewayUrlTemplate;

    public PairingCodeService(InstanceRepository instanceRepository,
                              @Value("${app.pairing-code.fixed-code:809393}") String fixedPairingCode,
                              @Value("${app.pairing-code.fixed-link-path:/?autoPair=1}") String fixedLinkPath,
                              @Value("${app.gateway.url-template:http://8.152.159.249:80/fun-claw/ui-controller/{instanceId}}") String gatewayUrlTemplate) {
        this.instanceRepository = instanceRepository;
        this.fixedPairingCode = fixedPairingCode == null ? "" : fixedPairingCode.trim();
        this.fixedLinkPath = normalizeFixedLinkPath(fixedLinkPath);
        this.gatewayUrlTemplate = gatewayUrlTemplate;
    }

    public PairingCodeResponse fetchPairingCode(UUID instanceId) {
        ClawInstanceDto instance = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "instance not found"));
        Instant fetchedAt = Instant.now();

        if (!StringUtils.hasText(fixedPairingCode)) {
            return new PairingCodeResponse(
                    instance.id(),
                    null,
                    null,
                    null,
                    "fixed pairing code is not configured",
                    fetchedAt
            );
        }

        String gatewayUrl = resolveGatewayUrl(instance.id(), instance.gatewayHostPort());
        if (!StringUtils.hasText(gatewayUrl)) {
            return new PairingCodeResponse(
                    instance.id(),
                    fixedPairingCode,
                    null,
                    null,
                    "instance gateway url is not available",
                    fetchedAt
            );
        }

        String pairingLink = buildPairingLink(gatewayUrl);
        String pairEndpoint = buildPairEndpoint(gatewayUrl);
        String requestExample = "POST " + pairEndpoint + " with header X-Pairing-Code: " + fixedPairingCode;

        return new PairingCodeResponse(
                instance.id(),
                fixedPairingCode,
                pairingLink,
                requestExample,
                "open pairing link in browser for auto-fill, or run request example manually",
                fetchedAt
        );
    }

    private String normalizeFixedLinkPath(String path) {
        if (!StringUtils.hasText(path)) {
            return "/?autoPair=1";
        }
        String trimmed = path.trim();
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }

    private String resolveGatewayUrl(UUID instanceId, Integer gatewayHostPort) {
        if (gatewayHostPort == null || !StringUtils.hasText(gatewayUrlTemplate)) {
            return null;
        }
        return gatewayUrlTemplate
                .replace("{port}", String.valueOf(gatewayHostPort))
                .replace("{instanceId}", instanceId.toString());
    }

    private String buildPairingLink(String gatewayUrl) {
        String trimmedGatewayUrl = gatewayUrl.trim();
        if (!StringUtils.hasText(trimmedGatewayUrl)) {
            return fixedLinkPath;
        }
        if (trimmedGatewayUrl.endsWith("/")) {
            return trimmedGatewayUrl.substring(0, trimmedGatewayUrl.length() - 1) + fixedLinkPath;
        }
        return trimmedGatewayUrl + fixedLinkPath;
    }

    private String buildPairEndpoint(String gatewayUrl) {
        String trimmedGatewayUrl = gatewayUrl.trim();
        if (!StringUtils.hasText(trimmedGatewayUrl)) {
            return "/pair";
        }
        if (trimmedGatewayUrl.endsWith("/")) {
            return trimmedGatewayUrl.substring(0, trimmedGatewayUrl.length() - 1) + "/pair";
        }
        return trimmedGatewayUrl + "/pair";
    }
}
