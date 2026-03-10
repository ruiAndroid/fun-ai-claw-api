package com.fun.ai.claw.api.service;

import com.fun.ai.claw.api.config.OpenApiProperties;
import com.fun.ai.claw.api.model.OpenClientAppRecord;
import com.fun.ai.claw.api.repository.OpenClientAppRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Service
public class OpenApiAuthService {

    private final OpenClientAppRepository openClientAppRepository;
    private final OpenApiProperties openApiProperties;

    public OpenApiAuthService(OpenClientAppRepository openClientAppRepository,
                              OpenApiProperties openApiProperties) {
        this.openClientAppRepository = openClientAppRepository;
        this.openApiProperties = openApiProperties;
    }

    public OpenClientAppRecord authenticate(HttpHeaders headers) {
        if (headers == null) {
            throw unauthorized("missing authentication headers");
        }
        String appId = headers.getFirst(openApiProperties.getAuth().getAppIdHeader());
        String appSecret = headers.getFirst(openApiProperties.getAuth().getAppSecretHeader());
        if (!StringUtils.hasText(appId) || !StringUtils.hasText(appSecret)) {
            throw unauthorized("missing open api credentials");
        }
        OpenClientAppRecord app = openClientAppRepository.findEnabledByAppId(appId.trim())
                .orElseThrow(() -> unauthorized("open app not found or disabled"));
        if (!appSecret.trim().equals(app.appSecret())) {
            throw unauthorized("invalid open api credentials");
        }
        return app;
    }

    public boolean matchesTokenHash(String storedHash, String plainToken) {
        if (!StringUtils.hasText(storedHash) || !StringUtils.hasText(plainToken)) {
            return false;
        }
        return sha256Hex(plainToken.trim()).equalsIgnoreCase(storedHash.trim());
    }

    public String hashToken(String plainToken) {
        if (!StringUtils.hasText(plainToken)) {
            throw new IllegalArgumentException("token must not be blank");
        }
        return sha256Hex(plainToken.trim());
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private ResponseStatusException unauthorized(String reason) {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, reason);
    }
}
