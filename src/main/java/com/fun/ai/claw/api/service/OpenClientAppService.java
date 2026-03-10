package com.fun.ai.claw.api.service;

import com.fun.ai.claw.api.model.OpenClientAppCreateRequest;
import com.fun.ai.claw.api.model.OpenClientAppCreateResponse;
import com.fun.ai.claw.api.model.OpenClientAppRecord;
import com.fun.ai.claw.api.model.OpenClientAppResponse;
import com.fun.ai.claw.api.model.OpenClientAppUpdateRequest;
import com.fun.ai.claw.api.model.ListResponse;
import com.fun.ai.claw.api.repository.OpenClientAppRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

@Service
public class OpenClientAppService {

    private final OpenClientAppRepository repository;
    private final SecureRandom secureRandom = new SecureRandom();

    public OpenClientAppService(OpenClientAppRepository repository) {
        this.repository = repository;
    }

    public ListResponse<OpenClientAppResponse> listApps() {
        List<OpenClientAppResponse> items = repository.findAll().stream()
                .map(OpenClientAppResponse::from)
                .toList();
        return new ListResponse<>(items);
    }

    @Transactional
    public OpenClientAppCreateResponse createApp(OpenClientAppCreateRequest request) {
        String appId = generateAppId();
        String plainSecret = generateSecret();
        String secretHash = "sha256:" + sha256Hex(plainSecret);
        Instant now = Instant.now();

        repository.insert(
                appId,
                request.name() != null ? request.name() : appId,
                secretHash,
                true,
                request.defaultInstanceId(),
                request.defaultAgentId(),
                now
        );

        OpenClientAppRecord record = repository.findByAppId(appId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "failed to read back created app"));

        return OpenClientAppCreateResponse.from(record, plainSecret);
    }

    @Transactional
    public OpenClientAppResponse updateApp(String appId, OpenClientAppUpdateRequest request) {
        repository.findByAppId(appId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "open app not found: " + appId));

        repository.update(
                appId,
                request.name(),
                request.enabled(),
                request.defaultInstanceId(),
                request.defaultAgentId(),
                Instant.now()
        );

        return repository.findByAppId(appId)
                .map(OpenClientAppResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "failed to read back updated app"));
    }

    @Transactional
    public void deleteApp(String appId) {
        int deleted = repository.deleteByAppId(appId);
        if (deleted == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "open app not found: " + appId);
        }
    }

    private String generateAppId() {
        byte[] bytes = new byte[5];
        secureRandom.nextBytes(bytes);
        return "fun-" + HexFormat.of().formatHex(bytes);
    }

    private String generateSecret() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
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
}
