package com.fun.ai.claw.api.service;

import com.fun.ai.claw.api.model.ClawInstanceDto;
import com.fun.ai.claw.api.model.PairingCodeResponse;
import com.fun.ai.claw.api.repository.InstanceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PairingCodeService {

    private static final Pattern PAIRED_TOKENS_PATTERN = Pattern.compile("(?is)\\bpaired_tokens\\s*=\\s*\\[(.*?)]");
    private static final Pattern TOKEN_FIELD_PATTERN = Pattern.compile("(?is)\\btoken\\s*=\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern QUOTED_STRING_PATTERN = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"");

    private final InstanceRepository instanceRepository;
    private final String fixedPairingCode;
    private final String fixedLinkPath;
    private final String gatewayUrlTemplate;
    private final String autoAuthQueryParam;
    private final String authTokenQueryParam;
    private final String dockerCommand;
    private final String containerPrefix;
    private final String gatewayConfigPath;
    private final long commandTimeoutSeconds;
    private final Duration authProbeTimeout;
    private final HttpClient authProbeClient;

    public PairingCodeService(InstanceRepository instanceRepository,
                              @Value("${app.pairing-code.fixed-code:809393}") String fixedPairingCode,
                              @Value("${app.pairing-code.fixed-link-path:/}") String fixedLinkPath,
                              @Value("${app.pairing-code.auto-auth-query-param:autoAuth}") String autoAuthQueryParam,
                              @Value("${app.pairing-code.auth-token-query-param:authToken}") String authTokenQueryParam,
                              @Value("${app.pairing-code.docker-command:docker}") String dockerCommand,
                              @Value("${app.pairing-code.container-prefix:funclaw}") String containerPrefix,
                              @Value("${app.pairing-code.gateway-config-path:/data/zeroclaw/config.toml}") String gatewayConfigPath,
                              @Value("${app.pairing-code.command-timeout-seconds:8}") long commandTimeoutSeconds,
                              @Value("${app.pairing-code.auth-probe-timeout-seconds:5}") long authProbeTimeoutSeconds,
                              @Value("${app.gateway.url-template:http://8.152.159.249:80/fun-claw/ui-controller/{instanceId}}") String gatewayUrlTemplate) {
        this.instanceRepository = instanceRepository;
        this.fixedPairingCode = fixedPairingCode == null ? "" : fixedPairingCode.trim();
        this.fixedLinkPath = normalizeFixedLinkPath(fixedLinkPath);
        this.autoAuthQueryParam = StringUtils.hasText(autoAuthQueryParam) ? autoAuthQueryParam.trim() : "autoAuth";
        this.authTokenQueryParam = StringUtils.hasText(authTokenQueryParam) ? authTokenQueryParam.trim() : "authToken";
        this.dockerCommand = StringUtils.hasText(dockerCommand) ? dockerCommand.trim() : "docker";
        this.containerPrefix = StringUtils.hasText(containerPrefix) ? containerPrefix.trim() : "funclaw";
        this.gatewayConfigPath = StringUtils.hasText(gatewayConfigPath) ? gatewayConfigPath.trim() : "/data/zeroclaw/config.toml";
        this.commandTimeoutSeconds = commandTimeoutSeconds > 0 ? commandTimeoutSeconds : 8;
        long probeSeconds = authProbeTimeoutSeconds > 0 ? authProbeTimeoutSeconds : 5;
        this.authProbeTimeout = Duration.ofSeconds(probeSeconds);
        this.authProbeClient = HttpClient.newBuilder()
                .connectTimeout(this.authProbeTimeout)
                .build();
        this.gatewayUrlTemplate = gatewayUrlTemplate;
    }

    public PairingCodeResponse fetchPairingCode(UUID instanceId) {
        ClawInstanceDto instance = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "instance not found"));
        Instant fetchedAt = Instant.now();

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
        if (isUnauthenticatedAccessAvailable(gatewayUrl)) {
            return new PairingCodeResponse(
                    instance.id(),
                    null,
                    pairingLink,
                    null,
                    "pairing is disabled (require_pairing=false), open link directly",
                    fetchedAt
            );
        }
        List<String> candidateTokens = readPairedTokens(instance.id());
        String usableToken = findUsableToken(candidateTokens, gatewayUrl);

        if (StringUtils.hasText(usableToken)) {
            String tokenLink = appendQueryParam(pairingLink, autoAuthQueryParam, "1");
            tokenLink = appendQueryParam(tokenLink, authTokenQueryParam, usableToken);
            return new PairingCodeResponse(
                    instance.id(),
                    fixedPairingCode,
                    tokenLink,
                    "Authorization: Bearer <validated token>",
                    "open pairing link for direct auto-login (no /pair request needed)",
                    fetchedAt
            );
        }

        if (!StringUtils.hasText(fixedPairingCode)) {
            return new PairingCodeResponse(
                    instance.id(),
                    null,
                    pairingLink,
                    null,
                    candidateTokens.isEmpty()
                            ? "paired token not found and fixed pairing code is not configured"
                            : "paired token candidates found but all were invalid",
                    fetchedAt
            );
        }

        String pairEndpoint = buildPairEndpoint(gatewayUrl);
        String requestExample = "POST " + pairEndpoint + " with header X-Pairing-Code: " + fixedPairingCode;

        return new PairingCodeResponse(
                instance.id(),
                fixedPairingCode,
                pairingLink,
                requestExample,
                candidateTokens.isEmpty()
                        ? "paired token not found; fallback to manual pairing request"
                        : "paired token candidates were invalid; fallback to manual pairing request",
                fetchedAt
        );
    }

    private List<String> readPairedTokens(UUID instanceId) {
        String containerName = containerPrefix + "-" + instanceId;
        CommandResult result = runCommand(
                dockerCommand,
                "exec",
                containerName,
                "/bin/busybox",
                "cat",
                gatewayConfigPath
        );
        if (result.exitCode != 0 || !StringUtils.hasText(result.output)) {
            return List.of();
        }
        return extractPairedTokens(result.output);
    }

    private List<String> extractPairedTokens(String configText) {
        Matcher tokenArrayMatcher = PAIRED_TOKENS_PATTERN.matcher(configText);
        if (!tokenArrayMatcher.find()) {
            return List.of();
        }

        String tokenArrayBody = tokenArrayMatcher.group(1);
        Set<String> tokens = new LinkedHashSet<>();

        Matcher namedTokenMatcher = TOKEN_FIELD_PATTERN.matcher(tokenArrayBody);
        while (namedTokenMatcher.find()) {
            String token = unescapeTomlString(namedTokenMatcher.group(1));
            if (StringUtils.hasText(token)) {
                tokens.add(token.trim());
            }
        }

        if (tokens.isEmpty()) {
            Matcher tokenMatcher = QUOTED_STRING_PATTERN.matcher(tokenArrayBody);
            while (tokenMatcher.find()) {
                String token = unescapeTomlString(tokenMatcher.group(1));
                if (StringUtils.hasText(token)) {
                    tokens.add(token.trim());
                }
            }
        }

        return new ArrayList<>(tokens);
    }

    private String unescapeTomlString(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    private String findUsableToken(List<String> candidateTokens, String gatewayUrl) {
        if (candidateTokens == null || candidateTokens.isEmpty()) {
            return null;
        }
        for (String token : candidateTokens) {
            if (!StringUtils.hasText(token)) {
                continue;
            }
            if (isTokenUsable(token.trim(), gatewayUrl)) {
                return token.trim();
            }
        }
        return null;
    }

    private boolean isTokenUsable(String token, String gatewayUrl) {
        try {
            URI statusUri = URI.create(buildApiStatusEndpoint(gatewayUrl));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(statusUri)
                    .timeout(authProbeTimeout)
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();
            HttpResponse<Void> response = authProbeClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String buildApiStatusEndpoint(String gatewayUrl) {
        String normalized = gatewayUrl.trim();
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized + "/api/status";
    }

    private boolean isUnauthenticatedAccessAvailable(String gatewayUrl) {
        try {
            URI statusUri = URI.create(buildApiStatusEndpoint(gatewayUrl));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(statusUri)
                    .timeout(authProbeTimeout)
                    .GET()
                    .build();
            HttpResponse<Void> response = authProbeClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception ignored) {
            return false;
        }
    }

    private CommandResult runCommand(String... command) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            process.getInputStream().transferTo(output);
            boolean finished = process.waitFor(commandTimeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new CommandResult(124, "command timed out");
            }
            return new CommandResult(process.exitValue(), output.toString(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            return new CommandResult(1, "io error: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new CommandResult(130, "interrupted");
        }
    }

    private String normalizeFixedLinkPath(String path) {
        if (!StringUtils.hasText(path)) {
            return "/";
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

    private String appendQueryParam(String url, String key, String value) {
        if (!StringUtils.hasText(url) || !StringUtils.hasText(key) || value == null) {
            return url;
        }
        String encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8);
        String encodedValue = URLEncoder.encode(value, StandardCharsets.UTF_8);
        String lowerUrl = url.toLowerCase(Locale.ROOT);
        if (lowerUrl.contains(encodedKey.toLowerCase(Locale.ROOT) + "=")) {
            return url;
        }
        return url + (url.contains("?") ? "&" : "?") + encodedKey + "=" + encodedValue;
    }

    private record CommandResult(int exitCode, String output) {
    }
}
