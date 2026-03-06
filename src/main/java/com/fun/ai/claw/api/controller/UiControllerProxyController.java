package com.fun.ai.claw.api.controller;

import com.fun.ai.claw.api.model.ClawInstanceDto;
import com.fun.ai.claw.api.repository.InstanceRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
public class UiControllerProxyController {
    private static final Logger log = LoggerFactory.getLogger(UiControllerProxyController.class);
    private static final String ZEROCLAW_DEFAULT_CONFIG_PATH = "/data/zeroclaw/config.toml";
    private static final Pattern CONFIG_PATH_PATTERN = Pattern.compile("(?i)\"config_path\"\\s*:\\s*\"[^\"]*\"");
    private static final Pattern CONFIG_PATH_CAMEL_PATTERN = Pattern.compile("(?i)\"configPath\"\\s*:\\s*\"[^\"]*\"");
    private static final Pattern PATH_PATTERN = Pattern.compile("(?i)\"path\"\\s*:\\s*\"[^\"]*\"");
    private static final Pattern FILE_PATH_PATTERN = Pattern.compile("(?i)\"file_path\"\\s*:\\s*\"[^\"]*\"");
    private static final Pattern FILE_PATH_CAMEL_PATTERN = Pattern.compile("(?i)\"filePath\"\\s*:\\s*\"[^\"]*\"");
    private static final Pattern STATUS_PAIRED_FALSE_PATTERN = Pattern.compile("(?i)\"paired\"\\s*:\\s*false");
    private static final Pattern API_KEYS_PATTERN = Pattern.compile("(?is)\\bapi_keys\\s*=\\s*\\[(.*?)]");
    private static final Pattern DEFAULT_PROVIDER_PATTERN = Pattern.compile("(?m)^\\s*default_provider\\s*=\\s*\"([^\"]+)\"\\s*$");
    private static final Pattern TOP_LEVEL_API_KEY_PATTERN = Pattern.compile("(?m)^\\s*api_key\\s*=\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*$");
    private static final Pattern SECTION_HEADER_PATTERN = Pattern.compile("(?m)^\\[[^\\]]+\\]\\s*$");
    private static final Pattern QUOTED_STRING_PATTERN = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"");

    private static final Set<String> SKIPPED_REQUEST_HEADERS = Set.of(
            "host",
            "content-length",
            "connection",
            "upgrade",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "transfer-encoding",
            "accept-encoding",
            "x-forwarded-for",
            "x-forwarded-host",
            "x-forwarded-proto"
    );
    private static final Set<String> SKIPPED_RESPONSE_HEADERS = Set.of(
            "connection",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade",
            "content-length"
    );

    private final InstanceRepository instanceRepository;
    private final HttpClient httpClient;
    private final String upstreamScheme;
    private final String upstreamHost;
    private final Duration requestTimeout;
    private final boolean forceConfigPathOnSave;
    private final String dockerCommand;
    private final String containerPrefix;
    private final boolean configSaveFallbackEnabled;
    private final String configSaveFallbackPath;
    private final Duration commandTimeout;
    private final String autoAuthQueryParam;
    private final String authTokenQueryParam;
    private final int upstreamRetryAttempts;
    private final long upstreamRetryDelayMillis;

    public UiControllerProxyController(InstanceRepository instanceRepository,
                                       @Value("${app.ui-controller.upstream-scheme:http}") String upstreamScheme,
                                       @Value("${app.ui-controller.upstream-host:127.0.0.1}") String upstreamHost,
                                       @Value("${app.ui-controller.request-timeout-seconds:60}") long requestTimeoutSeconds,
                                       @Value("${app.ui-controller.force-config-path-on-save:true}") boolean forceConfigPathOnSave,
                                       @Value("${app.ui-controller.docker-command:docker}") String dockerCommand,
                                       @Value("${app.ui-controller.container-prefix:funclaw}") String containerPrefix,
                                       @Value("${app.ui-controller.config-save-fallback-enabled:true}") boolean configSaveFallbackEnabled,
                                       @Value("${app.ui-controller.config-save-fallback-path:/data/zeroclaw/config.toml}") String configSaveFallbackPath,
                                       @Value("${app.ui-controller.command-timeout-seconds:15}") long commandTimeoutSeconds,
                                       @Value("${app.ui-controller.upstream-retry-attempts:8}") int upstreamRetryAttempts,
                                       @Value("${app.ui-controller.upstream-retry-delay-millis:300}") long upstreamRetryDelayMillis,
                                       @Value("${app.pairing-code.auto-auth-query-param:autoAuth}") String autoAuthQueryParam,
                                       @Value("${app.pairing-code.auth-token-query-param:authToken}") String authTokenQueryParam) {
        this.instanceRepository = instanceRepository;
        this.upstreamScheme = normalizeScheme(upstreamScheme);
        this.upstreamHost = requireHost(upstreamHost);
        long timeoutSeconds = requestTimeoutSeconds > 0 ? requestTimeoutSeconds : 60;
        this.requestTimeout = Duration.ofSeconds(timeoutSeconds);
        this.forceConfigPathOnSave = forceConfigPathOnSave;
        this.dockerCommand = dockerCommand;
        this.containerPrefix = containerPrefix;
        this.configSaveFallbackEnabled = configSaveFallbackEnabled;
        this.configSaveFallbackPath = configSaveFallbackPath;
        long commandSeconds = commandTimeoutSeconds > 0 ? commandTimeoutSeconds : 15;
        this.commandTimeout = Duration.ofSeconds(commandSeconds);
        this.upstreamRetryAttempts = upstreamRetryAttempts > 0 ? upstreamRetryAttempts : 1;
        this.upstreamRetryDelayMillis = Math.max(upstreamRetryDelayMillis, 0L);
        this.autoAuthQueryParam = StringUtils.hasText(autoAuthQueryParam) ? autoAuthQueryParam.trim() : "autoAuth";
        this.authTokenQueryParam = StringUtils.hasText(authTokenQueryParam) ? authTokenQueryParam.trim() : "authToken";
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        log.info("ui proxy config-save fallback enabled={}, dockerCommand={}, containerPrefix={}, fallbackPath={}, upstreamRetryAttempts={}, upstreamRetryDelayMillis={}",
                this.configSaveFallbackEnabled,
                this.dockerCommand,
                this.containerPrefix,
                this.configSaveFallbackPath,
                this.upstreamRetryAttempts,
                this.upstreamRetryDelayMillis);
    }

    @RequestMapping(
            path = {
                    "/fun-claw/ui-controller/{instanceId}/api/events",
                    "/fun-claw/ui-controller/{instanceId}/api/events/**",
                    "/ui-controller/{instanceId}/api/events",
                    "/ui-controller/{instanceId}/api/events/**",
                    "/{instanceId:[0-9a-fA-F\\-]{36}}/api/events",
                    "/{instanceId:[0-9a-fA-F\\-]{36}}/api/events/**"
            },
            method = {
                    RequestMethod.GET
            },
            headers = "!Upgrade"
    )
    public ResponseEntity<StreamingResponseBody> proxyEvents(@PathVariable UUID instanceId,
                                                             HttpServletRequest request) {
        ClawInstanceDto instance = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "instance not found"));

        Integer gatewayHostPort = instance.gatewayHostPort();
        if (gatewayHostPort == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "instance gateway host port is not assigned");
        }
        if (gatewayHostPort <= 0 || gatewayHostPort > 65535) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "invalid gateway host port");
        }

        URI targetUri = buildTargetUri(instanceId, gatewayHostPort, request);
        return proxyEventStream(request, targetUri);
    }

    @RequestMapping(
            path = {
                    "/fun-claw/ui-controller/{instanceId}",
                    "/fun-claw/ui-controller/{instanceId}/**",
                    "/ui-controller/{instanceId}",
                    "/ui-controller/{instanceId}/**",
                    "/{instanceId:[0-9a-fA-F\\-]{36}}",
                    "/{instanceId:[0-9a-fA-F\\-]{36}}/**"
            },
            method = {
                    RequestMethod.GET,
                    RequestMethod.POST,
                    RequestMethod.PUT,
                    RequestMethod.PATCH,
                    RequestMethod.DELETE,
                    RequestMethod.HEAD,
                    RequestMethod.OPTIONS
            },
            headers = "!Upgrade"
    )
    public ResponseEntity<byte[]> proxy(@PathVariable UUID instanceId,
                                        HttpServletRequest request,
                                        @RequestBody(required = false) byte[] requestBody) {
        ClawInstanceDto instance = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "instance not found"));

        Integer gatewayHostPort = instance.gatewayHostPort();
        if (gatewayHostPort == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "instance gateway host port is not assigned");
        }
        if (gatewayHostPort <= 0 || gatewayHostPort > 65535) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "invalid gateway host port");
        }

        URI targetUri = buildTargetUri(instanceId, gatewayHostPort, request);
        byte[] normalizedRequestBody = rewriteConfigSavePayloadIfNeeded(instanceId, request, requestBody, targetUri);
        HttpRequest outboundRequest = buildOutboundRequest(request, normalizedRequestBody, targetUri);
        HttpResponse<byte[]> upstreamResponse = send(outboundRequest);
        if (isConfigEndpoint(targetUri.getPath()) && upstreamResponse.statusCode() >= 500) {
            String errorBody = upstreamResponse.body() == null
                    ? ""
                    : new String(upstreamResponse.body(), StandardCharsets.UTF_8);
            log.warn("ui proxy /api/config upstream error status={}, uri={}, body={}",
                    upstreamResponse.statusCode(),
                    targetUri,
                    errorBody);
        }
        if (shouldRetryConfigSaveWithForcedPath(request, targetUri, upstreamResponse)) {
            URI retryUri = ensureConfigPathQueryPresent(targetUri);
            if (!retryUri.equals(targetUri)) {
                log.warn("ui proxy retry /api/config with forced config_path: {}", retryUri);
                HttpRequest retryRequest = buildOutboundRequest(request, normalizedRequestBody, retryUri);
                upstreamResponse = send(retryRequest);
                if (upstreamResponse.statusCode() >= 500) {
                    String retryErrorBody = upstreamResponse.body() == null
                            ? ""
                            : new String(upstreamResponse.body(), StandardCharsets.UTF_8);
                    log.warn("ui proxy /api/config retry error status={}, uri={}, body={}",
                            upstreamResponse.statusCode(),
                            retryUri,
                            retryErrorBody);
                }
            }
        }
        if (shouldApplyDirectConfigSaveFallback(request, targetUri, normalizedRequestBody, upstreamResponse)) {
            log.warn("ui proxy trigger config-save fallback for instanceId={}, uri={}", instanceId, targetUri);
            if (saveConfigFileToContainer(instanceId, normalizedRequestBody)) {
                HttpHeaders fallbackHeaders = new HttpHeaders();
                fallbackHeaders.set(HttpHeaders.CONTENT_TYPE, "application/json");
                byte[] body = "{\"saved\":true,\"source\":\"ui-proxy-fallback\"}".getBytes(StandardCharsets.UTF_8);
                return new ResponseEntity<>(body, fallbackHeaders, HttpStatusCode.valueOf(200));
            }
            log.warn("ui proxy fallback execution failed for instanceId={}, uri={}", instanceId, targetUri);
        }
        return buildResponse(instanceId, targetUri, upstreamResponse);
    }

    private ResponseEntity<StreamingResponseBody> proxyEventStream(HttpServletRequest request, URI targetUri) {
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.set(HttpHeaders.CONTENT_TYPE, "text/event-stream;charset=UTF-8");
        responseHeaders.set(HttpHeaders.CACHE_CONTROL, "no-cache");
        responseHeaders.set("X-Accel-Buffering", "no");

        StreamingResponseBody stream = outputStream -> {
            HttpRequest outboundRequest = buildEventStreamRequest(request, targetUri);
            outputStream.write(": connected\n\n".getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
            try {
                HttpResponse<InputStream> upstreamResponse = sendEventStream(outboundRequest);
                try (InputStream body = upstreamResponse.body()) {
                    body.transferTo(outputStream);
                }
            } catch (ResponseStatusException ex) {
                String message = ex.getReason() == null ? "event stream proxy error" : ex.getReason();
                log.warn("ui proxy event-stream failed uri={} reason={}", targetUri, message);
                String escaped = message.replace("\\", "\\\\").replace("\"", "\\\"");
                String errorEvent = "event: error\ndata: {\"type\":\"error\",\"message\":\"" + escaped + "\"}\n\n";
                outputStream.write(errorEvent.getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
            }
        };
        return new ResponseEntity<>(stream, responseHeaders, HttpStatus.OK);
    }

    private URI buildTargetUri(UUID instanceId, int targetPort, HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String downstreamPath = "/";
        String marker = "/" + instanceId;
        int markerIndex = requestUri.indexOf(marker);
        if (markerIndex >= 0) {
            int downstreamStart = markerIndex + marker.length();
            if (requestUri.length() > downstreamStart) {
                downstreamPath = requestUri.substring(downstreamStart);
                if (!downstreamPath.startsWith("/")) {
                    downstreamPath = "/" + downstreamPath;
                }
            }
        }

        String queryString = request.getQueryString();
        if (isConfigEndpoint(downstreamPath)) {
            queryString = appendConfigPathQueryIfMissing(queryString);
        }

        StringBuilder uriBuilder = new StringBuilder()
                .append(upstreamScheme)
                .append("://")
                .append(upstreamHost)
                .append(":")
                .append(targetPort)
                .append(downstreamPath);
        if (StringUtils.hasText(queryString)) {
            uriBuilder.append("?").append(queryString);
        }
        return URI.create(uriBuilder.toString());
    }

    private String appendConfigPathQueryIfMissing(String queryString) {
        if (!StringUtils.hasText(queryString)) {
            log.info("ui proxy append config path query for /api/config request");
            return "config_path=" + ZEROCLAW_DEFAULT_CONFIG_PATH
                    + "&configPath=" + ZEROCLAW_DEFAULT_CONFIG_PATH
                    + "&path=" + ZEROCLAW_DEFAULT_CONFIG_PATH
                    + "&file_path=" + ZEROCLAW_DEFAULT_CONFIG_PATH
                    + "&filePath=" + ZEROCLAW_DEFAULT_CONFIG_PATH;
        }
        String lower = queryString.toLowerCase(Locale.ROOT);
        boolean hasSnake = lower.contains("config_path=");
        boolean hasCamel = lower.contains("configpath=");
        boolean hasPath = lower.contains("path=");
        boolean hasFileSnake = lower.contains("file_path=");
        boolean hasFileCamel = lower.contains("filepath=");
        if (hasSnake && hasCamel && hasPath && hasFileSnake && hasFileCamel) {
            return queryString;
        }
        log.info("ui proxy append missing config path fields for /api/config request");
        StringBuilder builder = new StringBuilder(queryString);
        if (!hasSnake) {
            builder.append("&config_path=").append(ZEROCLAW_DEFAULT_CONFIG_PATH);
        }
        if (!hasCamel) {
            builder.append("&configPath=").append(ZEROCLAW_DEFAULT_CONFIG_PATH);
        }
        if (!hasPath) {
            builder.append("&path=").append(ZEROCLAW_DEFAULT_CONFIG_PATH);
        }
        if (!hasFileSnake) {
            builder.append("&file_path=").append(ZEROCLAW_DEFAULT_CONFIG_PATH);
        }
        if (!hasFileCamel) {
            builder.append("&filePath=").append(ZEROCLAW_DEFAULT_CONFIG_PATH);
        }
        return builder.toString();
    }

    private boolean isConfigEndpoint(String path) {
        if (!StringUtils.hasText(path)) {
            return false;
        }
        return "/api/config".equals(path)
                || "/api/config/".equals(path)
                || path.startsWith("/api/config/");
    }

    private boolean shouldRetryConfigSaveWithForcedPath(HttpServletRequest inboundRequest,
                                                        URI targetUri,
                                                        HttpResponse<byte[]> upstreamResponse) {
        if (upstreamResponse == null || targetUri == null) {
            return false;
        }
        String method = inboundRequest.getMethod();
        if (!"PUT".equalsIgnoreCase(method) && !"POST".equalsIgnoreCase(method) && !"PATCH".equalsIgnoreCase(method)) {
            return false;
        }
        if (!isConfigEndpoint(targetUri.getPath())) {
            return false;
        }
        if (upstreamResponse.statusCode() < 500) {
            return false;
        }
        byte[] body = upstreamResponse.body();
        if (body == null || body.length == 0) {
            return false;
        }
        String errorText = new String(body, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        return errorText.contains("config path must have a parent directory");
    }

    private URI ensureConfigPathQueryPresent(URI uri) {
        String query = uri.getRawQuery();
        if (!StringUtils.hasText(query)) {
            return URI.create(uri.toString()
                    + "?config_path=" + ZEROCLAW_DEFAULT_CONFIG_PATH
                    + "&configPath=" + ZEROCLAW_DEFAULT_CONFIG_PATH
                    + "&path=" + ZEROCLAW_DEFAULT_CONFIG_PATH
                    + "&file_path=" + ZEROCLAW_DEFAULT_CONFIG_PATH
                    + "&filePath=" + ZEROCLAW_DEFAULT_CONFIG_PATH);
        }
        String lower = query.toLowerCase(Locale.ROOT);
        boolean hasSnake = lower.contains("config_path=");
        boolean hasCamel = lower.contains("configpath=");
        boolean hasPath = lower.contains("path=");
        boolean hasFileSnake = lower.contains("file_path=");
        boolean hasFileCamel = lower.contains("filepath=");
        if (hasSnake && hasCamel && hasPath && hasFileSnake && hasFileCamel) {
            return uri;
        }
        StringBuilder uriBuilder = new StringBuilder(uri.toString());
        if (!hasSnake) {
            uriBuilder.append("&config_path=").append(ZEROCLAW_DEFAULT_CONFIG_PATH);
        }
        if (!hasCamel) {
            uriBuilder.append("&configPath=").append(ZEROCLAW_DEFAULT_CONFIG_PATH);
        }
        if (!hasPath) {
            uriBuilder.append("&path=").append(ZEROCLAW_DEFAULT_CONFIG_PATH);
        }
        if (!hasFileSnake) {
            uriBuilder.append("&file_path=").append(ZEROCLAW_DEFAULT_CONFIG_PATH);
        }
        if (!hasFileCamel) {
            uriBuilder.append("&filePath=").append(ZEROCLAW_DEFAULT_CONFIG_PATH);
        }
        return URI.create(uriBuilder.toString());
    }

    private boolean shouldApplyDirectConfigSaveFallback(HttpServletRequest inboundRequest,
                                                        URI targetUri,
                                                        byte[] requestBody,
                                                        HttpResponse<byte[]> upstreamResponse) {
        if (!configSaveFallbackEnabled || targetUri == null || requestBody == null || requestBody.length == 0) {
            log.info("ui proxy skip fallback: enabled={}, hasUri={}, hasBody={}",
                    configSaveFallbackEnabled, targetUri != null, requestBody != null && requestBody.length > 0);
            return false;
        }
        if (!isConfigEndpoint(targetUri.getPath())) {
            log.info("ui proxy skip fallback: non config endpoint path={}", targetUri.getPath());
            return false;
        }
        String method = inboundRequest.getMethod();
        if (!"PUT".equalsIgnoreCase(method) && !"POST".equalsIgnoreCase(method) && !"PATCH".equalsIgnoreCase(method)) {
            log.info("ui proxy skip fallback: unsupported method={}", method);
            return false;
        }
        if (upstreamResponse == null || upstreamResponse.statusCode() < 500) {
            log.info("ui proxy skip fallback: upstream status={}", upstreamResponse == null ? -1 : upstreamResponse.statusCode());
            return false;
        }
        byte[] body = upstreamResponse.body();
        if (body == null || body.length == 0) {
            log.info("ui proxy skip fallback: empty upstream error body");
            return false;
        }
        String errorText = new String(body, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        log.info("ui proxy fallback check upstream error={}", errorText);
        return errorText.contains("config path must have a parent directory");
    }

    private boolean saveConfigFileToContainer(UUID instanceId, byte[] configBytes) {
        if (!StringUtils.hasText(configSaveFallbackPath) || !StringUtils.hasText(containerPrefix)) {
            return false;
        }
        String path = configSaveFallbackPath.trim();
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash <= 0) {
            log.warn("ui proxy fallback save skipped: invalid config path {}", path);
            return false;
        }
        String directory = path.substring(0, lastSlash);
        String containerName = containerPrefix.trim() + "-" + instanceId;

        CommandResult mkdirResult = runProcess(List.of(
                dockerCommand,
                "exec",
                containerName,
                "/bin/busybox",
                "mkdir",
                "-p",
                directory
        ), null);
        if (mkdirResult.exitCode != 0) {
            log.warn("ui proxy fallback mkdir failed for {}: {}", containerName, mkdirResult.output);
            return false;
        }

        CommandResult writeResult = runProcess(List.of(
                dockerCommand,
                "exec",
                "-i",
                containerName,
                "/bin/busybox",
                "dd",
                "of=" + path,
                "conv=fsync"
        ), configBytes);
        if (writeResult.exitCode != 0) {
            log.warn("ui proxy fallback write failed for {}: {}", containerName, writeResult.output);
            return false;
        }
        log.info("ui proxy fallback saved config directly to container {} at {}", containerName, path);
        return true;
    }

    private CommandResult runProcess(List<String> command, byte[] stdinBytes) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            if (stdinBytes != null && stdinBytes.length > 0) {
                try (OutputStream os = process.getOutputStream()) {
                    os.write(stdinBytes);
                    os.flush();
                }
            } else {
                process.getOutputStream().close();
            }

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            process.getInputStream().transferTo(output);
            boolean finished = process.waitFor(commandTimeout.toMillis(), TimeUnit.MILLISECONDS);
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

    private record CommandResult(int exitCode, String output) {
    }

    private HttpRequest buildOutboundRequest(HttpServletRequest inboundRequest, byte[] requestBody, URI targetUri) {
        return buildOutboundRequest(inboundRequest, requestBody, targetUri, null);
    }

    private HttpRequest buildOutboundRequest(HttpServletRequest inboundRequest,
                                             byte[] requestBody,
                                             URI targetUri,
                                             String forcedContentType) {
        HttpRequest.BodyPublisher bodyPublisher = (requestBody == null || requestBody.length == 0)
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(requestBody);

        HttpRequest.Builder outboundBuilder = HttpRequest.newBuilder()
                .uri(targetUri)
                .timeout(requestTimeout)
                .method(inboundRequest.getMethod(), bodyPublisher);

        copyRequestHeaders(inboundRequest, outboundBuilder);
        if (StringUtils.hasText(forcedContentType)) {
            outboundBuilder.setHeader("Content-Type", forcedContentType);
        }
        addConfigPathHeadersIfNeeded(outboundBuilder, targetUri);
        return outboundBuilder.build();
    }

    private HttpRequest buildEventStreamRequest(HttpServletRequest inboundRequest, URI targetUri) {
        HttpRequest.Builder outboundBuilder = HttpRequest.newBuilder()
                .uri(targetUri)
                .GET();
        copyRequestHeaders(inboundRequest, outboundBuilder);
        addConfigPathHeadersIfNeeded(outboundBuilder, targetUri);
        return outboundBuilder.build();
    }

    private void addConfigPathHeadersIfNeeded(HttpRequest.Builder outboundBuilder, URI targetUri) {
        if (targetUri == null || !isConfigEndpoint(targetUri.getPath())) {
            return;
        }
        outboundBuilder.setHeader("X-Config-Path", ZEROCLAW_DEFAULT_CONFIG_PATH);
        outboundBuilder.setHeader("Config-Path", ZEROCLAW_DEFAULT_CONFIG_PATH);
        outboundBuilder.setHeader("X-ZeroClaw-Config-Path", ZEROCLAW_DEFAULT_CONFIG_PATH);
        outboundBuilder.setHeader("X-ConfigPath", ZEROCLAW_DEFAULT_CONFIG_PATH);
    }

    private byte[] rewriteConfigSavePayloadIfNeeded(UUID instanceId,
                                                    HttpServletRequest inboundRequest,
                                                    byte[] requestBody,
                                                    URI targetUri) {
        if (requestBody == null || requestBody.length == 0 || targetUri == null) {
            return requestBody;
        }
        String method = inboundRequest.getMethod();
        if (!"POST".equalsIgnoreCase(method) && !"PUT".equalsIgnoreCase(method) && !"PATCH".equalsIgnoreCase(method)) {
            return requestBody;
        }
        if (!"/api/config".equals(targetUri.getPath())) {
            return requestBody;
        }

        byte[] normalizedBody = restoreMaskedApiKeysIfNeeded(instanceId, inboundRequest, requestBody);
        normalizedBody = normalizeCustomProviderApiKeyIfNeeded(inboundRequest, normalizedBody);
        if (!forceConfigPathOnSave) {
            return normalizedBody;
        }

        String contentType = inboundRequest.getContentType();
        if (!StringUtils.hasText(contentType) || !contentType.toLowerCase(Locale.ROOT).contains("application/json")) {
            return normalizedBody;
        }

        String raw = new String(normalizedBody, StandardCharsets.UTF_8);
        String rewritten = CONFIG_PATH_PATTERN.matcher(raw)
                .replaceAll("\"config_path\":\"" + ZEROCLAW_DEFAULT_CONFIG_PATH + "\"");
        rewritten = CONFIG_PATH_CAMEL_PATTERN.matcher(rewritten)
                .replaceAll("\"configPath\":\"" + ZEROCLAW_DEFAULT_CONFIG_PATH + "\"");
        rewritten = PATH_PATTERN.matcher(rewritten)
                .replaceAll("\"path\":\"" + ZEROCLAW_DEFAULT_CONFIG_PATH + "\"");
        rewritten = FILE_PATH_PATTERN.matcher(rewritten)
                .replaceAll("\"file_path\":\"" + ZEROCLAW_DEFAULT_CONFIG_PATH + "\"");
        rewritten = FILE_PATH_CAMEL_PATTERN.matcher(rewritten)
                .replaceAll("\"filePath\":\"" + ZEROCLAW_DEFAULT_CONFIG_PATH + "\"");

        if (rewritten.equals(raw) && rewritten.trim().endsWith("}")) {
            String suffix = rewritten.trim();
            int braceIndex = rewritten.lastIndexOf('}');
            if (braceIndex > 0) {
                boolean hasFields = suffix.length() > 2 && suffix.charAt(0) == '{';
                String insertion = hasFields
                        ? ",\"config_path\":\"" + ZEROCLAW_DEFAULT_CONFIG_PATH + "\",\"path\":\"" + ZEROCLAW_DEFAULT_CONFIG_PATH + "\""
                        : "\"config_path\":\"" + ZEROCLAW_DEFAULT_CONFIG_PATH + "\",\"path\":\"" + ZEROCLAW_DEFAULT_CONFIG_PATH + "\"";
                rewritten = rewritten.substring(0, braceIndex) + insertion + rewritten.substring(braceIndex);
            }
        }

        if (rewritten.equals(raw)) {
            return normalizedBody;
        }
        return rewritten.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] restoreMaskedApiKeysIfNeeded(UUID instanceId,
                                                HttpServletRequest inboundRequest,
                                                byte[] requestBody) {
        String contentType = inboundRequest.getContentType();
        if (!StringUtils.hasText(contentType) || !contentType.toLowerCase(Locale.ROOT).contains("toml")) {
            return requestBody;
        }
        String incoming = new String(requestBody, StandardCharsets.UTF_8);
        if (!incoming.contains("***MASKED***")) {
            return requestBody;
        }

        List<String> incomingKeys = extractApiKeys(incoming);
        if (incomingKeys.isEmpty()) {
            return requestBody;
        }
        boolean hasMasked = incomingKeys.stream().anyMatch("***MASKED***"::equals);
        if (!hasMasked) {
            return requestBody;
        }

        String currentConfig = readCurrentConfigFromContainer(instanceId);
        if (!StringUtils.hasText(currentConfig)) {
            return requestBody;
        }
        List<String> currentKeys = extractApiKeys(currentConfig);
        if (currentKeys.isEmpty()) {
            return requestBody;
        }

        List<String> merged = new ArrayList<>(incomingKeys.size());
        for (int i = 0; i < incomingKeys.size(); i++) {
            String key = incomingKeys.get(i);
            if ("***MASKED***".equals(key) && i < currentKeys.size() && StringUtils.hasText(currentKeys.get(i))) {
                merged.add(currentKeys.get(i));
            } else {
                merged.add(key);
            }
        }

        String rewritten = replaceApiKeys(incoming, merged);
        if (!rewritten.equals(incoming)) {
            log.info("ui proxy restored masked api_keys placeholders from existing config for instanceId={}", instanceId);
            return rewritten.getBytes(StandardCharsets.UTF_8);
        }
        return requestBody;
    }

    private byte[] normalizeCustomProviderApiKeyIfNeeded(HttpServletRequest inboundRequest, byte[] requestBody) {
        if (requestBody == null || requestBody.length == 0) {
            return requestBody;
        }
        String contentType = inboundRequest.getContentType();
        if (!StringUtils.hasText(contentType) || !contentType.toLowerCase(Locale.ROOT).contains("toml")) {
            return requestBody;
        }

        String raw = new String(requestBody, StandardCharsets.UTF_8);
        String rewritten = normalizeCustomProviderApiKey(raw);
        if (rewritten.equals(raw)) {
            return requestBody;
        }
        return rewritten.getBytes(StandardCharsets.UTF_8);
    }

    private String normalizeCustomProviderApiKey(String toml) {
        if (!StringUtils.hasText(toml)) {
            return toml;
        }
        String provider = extractDefaultProvider(toml);
        if (!StringUtils.hasText(provider)) {
            return toml;
        }
        String providerLower = provider.toLowerCase(Locale.ROOT).trim();
        if (!providerLower.startsWith("custom:") && !providerLower.startsWith("anthropic-custom:")) {
            return toml;
        }

        int firstSectionStart = findFirstSectionStart(toml);
        String topLevel = firstSectionStart >= 0 ? toml.substring(0, firstSectionStart) : toml;
        String remainder = firstSectionStart >= 0 ? toml.substring(firstSectionStart) : "";

        Matcher topApiKeyMatcher = TOP_LEVEL_API_KEY_PATTERN.matcher(topLevel);
        if (topApiKeyMatcher.find()) {
            String existing = unescapeTomlString(topApiKeyMatcher.group(1)).trim();
            if (StringUtils.hasText(existing) && !"***MASKED***".equals(existing)) {
                return toml;
            }
        }

        String candidate = firstUsableApiKey(extractApiKeys(toml));
        if (!StringUtils.hasText(candidate)) {
            return toml;
        }

        String sanitizedTopLevel = TOP_LEVEL_API_KEY_PATTERN.matcher(topLevel).replaceAll("");
        if (!sanitizedTopLevel.isEmpty() && !sanitizedTopLevel.endsWith("\n")) {
            sanitizedTopLevel += "\n";
        }
        sanitizedTopLevel += "api_key = \"" + escapeTomlString(candidate) + "\"\n";
        log.info("ui proxy normalized top-level api_key from api_keys list for custom provider");
        return sanitizedTopLevel + remainder;
    }

    private String extractDefaultProvider(String toml) {
        Matcher matcher = DEFAULT_PROVIDER_PATTERN.matcher(toml);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1);
    }

    private int findFirstSectionStart(String toml) {
        Matcher matcher = SECTION_HEADER_PATTERN.matcher(toml);
        if (!matcher.find()) {
            return -1;
        }
        return matcher.start();
    }

    private String readCurrentConfigFromContainer(UUID instanceId) {
        if (!StringUtils.hasText(containerPrefix) || !StringUtils.hasText(configSaveFallbackPath)) {
            return null;
        }
        String containerName = containerPrefix.trim() + "-" + instanceId;
        String configPath = configSaveFallbackPath.trim();
        CommandResult readResult = runProcess(List.of(
                dockerCommand,
                "exec",
                containerName,
                "/bin/busybox",
                "cat",
                configPath
        ), null);
        if (readResult.exitCode != 0 || !StringUtils.hasText(readResult.output)) {
            return null;
        }
        return readResult.output;
    }

    private List<String> extractApiKeys(String toml) {
        if (!StringUtils.hasText(toml)) {
            return List.of();
        }
        Matcher matcher = API_KEYS_PATTERN.matcher(toml);
        if (!matcher.find()) {
            return List.of();
        }
        String arrayBody = matcher.group(1);
        Matcher quotedMatcher = QUOTED_STRING_PATTERN.matcher(arrayBody);
        List<String> keys = new ArrayList<>();
        while (quotedMatcher.find()) {
            keys.add(unescapeTomlString(quotedMatcher.group(1)));
        }
        return keys;
    }

    private String firstUsableApiKey(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return null;
        }
        for (String key : keys) {
            if (!StringUtils.hasText(key)) {
                continue;
            }
            String trimmed = key.trim();
            if ("***MASKED***".equals(trimmed)) {
                continue;
            }
            return trimmed;
        }
        return null;
    }

    private String replaceApiKeys(String toml, List<String> keys) {
        Matcher matcher = API_KEYS_PATTERN.matcher(toml);
        if (!matcher.find()) {
            return toml;
        }
        StringJoiner joiner = new StringJoiner(", ", "api_keys = [", "]");
        for (String key : keys) {
            String safe = key == null ? "" : key;
            joiner.add("\"" + escapeTomlString(safe) + "\"");
        }
        String replacement = joiner.toString();
        return toml.substring(0, matcher.start()) + replacement + toml.substring(matcher.end());
    }

    private String unescapeTomlString(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    private String escapeTomlString(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    private void copyRequestHeaders(HttpServletRequest inboundRequest, HttpRequest.Builder outboundBuilder) {
        Enumeration<String> headerNames = inboundRequest.getHeaderNames();
        while (headerNames != null && headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            if (shouldSkipRequestHeader(headerName)) {
                continue;
            }
            Enumeration<String> headerValues = inboundRequest.getHeaders(headerName);
            while (headerValues.hasMoreElements()) {
                outboundBuilder.header(headerName, headerValues.nextElement());
            }
        }

        if (StringUtils.hasText(inboundRequest.getHeader("Host"))) {
            outboundBuilder.setHeader("X-Forwarded-Host", inboundRequest.getHeader("Host"));
        }
        outboundBuilder.setHeader("X-Forwarded-Proto", inboundRequest.getScheme());

        String priorForwardedFor = inboundRequest.getHeader("X-Forwarded-For");
        String remoteAddress = inboundRequest.getRemoteAddr();
        if (StringUtils.hasText(remoteAddress)) {
            String nextForwardedFor = StringUtils.hasText(priorForwardedFor)
                    ? priorForwardedFor + ", " + remoteAddress
                    : remoteAddress;
            outboundBuilder.setHeader("X-Forwarded-For", nextForwardedFor);
        }
    }

    private HttpResponse<byte[]> send(HttpRequest outboundRequest) {
        IOException lastIoException = null;
        for (int attempt = 1; attempt <= upstreamRetryAttempts; attempt++) {
            try {
                return httpClient.send(outboundRequest, HttpResponse.BodyHandlers.ofByteArray());
            } catch (IOException ex) {
                lastIoException = ex;
                if (attempt >= upstreamRetryAttempts) {
                    break;
                }
                log.warn("ui proxy upstream request retry {}/{} uri={} reason={}",
                        attempt,
                        upstreamRetryAttempts,
                        outboundRequest.uri(),
                        ex.getMessage());
                if (upstreamRetryDelayMillis > 0) {
                    try {
                        Thread.sleep(upstreamRetryDelayMillis);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "ui proxy request interrupted");
                    }
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "ui proxy request interrupted");
            }
        }
        String message = lastIoException == null ? "unknown io error" : lastIoException.getMessage();
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "failed to proxy ui request: " + message);
    }

    private HttpResponse<InputStream> sendEventStream(HttpRequest outboundRequest) {
        IOException lastIoException = null;
        for (int attempt = 1; attempt <= upstreamRetryAttempts; attempt++) {
            try {
                return httpClient.send(outboundRequest, HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException ex) {
                lastIoException = ex;
                if (attempt >= upstreamRetryAttempts) {
                    break;
                }
                log.warn("ui proxy upstream event-stream retry {}/{} uri={} reason={}",
                        attempt,
                        upstreamRetryAttempts,
                        outboundRequest.uri(),
                        ex.getMessage());
                if (upstreamRetryDelayMillis > 0) {
                    try {
                        Thread.sleep(upstreamRetryDelayMillis);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "ui proxy event-stream interrupted");
                    }
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "ui proxy event-stream interrupted");
            }
        }
        String message = lastIoException == null ? "unknown io error" : lastIoException.getMessage();
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "failed to proxy ui event-stream: " + message);
    }

    private ResponseEntity<byte[]> buildResponse(UUID instanceId, URI targetUri, HttpResponse<byte[]> upstreamResponse) {
        byte[] responseBody = rewriteUiAssetRootPath(instanceId, targetUri, upstreamResponse);
        HttpHeaders responseHeaders = new HttpHeaders();
        upstreamResponse.headers().map().forEach((headerName, values) -> {
            if (shouldSkipResponseHeader(headerName)) {
                return;
            }
            if ("set-cookie".equalsIgnoreCase(headerName)) {
                responseHeaders.put(headerName, rewriteSetCookiePaths(values));
                return;
            }
            responseHeaders.put(headerName, new ArrayList<>(values));
        });

        return new ResponseEntity<>(
                responseBody,
                responseHeaders,
                HttpStatusCode.valueOf(upstreamResponse.statusCode())
        );
    }

    private byte[] rewriteUiAssetRootPath(UUID instanceId, URI targetUri, HttpResponse<byte[]> upstreamResponse) {
        String contentType = upstreamResponse.headers()
                .firstValue("content-type")
                .orElse("")
                .toLowerCase(Locale.ROOT);

        boolean htmlContent = contentType.contains("text/html");
        boolean statusJson = isStatusEndpoint(targetUri != null ? targetUri.getPath() : null)
                && contentType.contains("application/json");
        boolean rewritable = contentType.contains("text/html")
                || contentType.contains("javascript")
                || contentType.contains("application/json");
        byte[] source = upstreamResponse.body();
        if (!rewritable || source == null || source.length == 0) {
            return source;
        }

        String raw = new String(source, StandardCharsets.UTF_8);
        String uiBase = "/fun-claw/ui-controller/" + instanceId;
        String rewritten = raw
                .replace("\"/_app/", "\"" + uiBase + "/_app/")
                .replace("'/_app/", "'" + uiBase + "/_app/")
                .replace("\"/api/\"", "\"" + uiBase + "/api/\"")
                .replace("'/api/'", "'" + uiBase + "/api/'")
                .replace("\"/api/", "\"" + uiBase + "/api/")
                .replace("'/api/", "'" + uiBase + "/api/")
                .replace("\"/api\"", "\"" + uiBase + "/api\"")
                .replace("'/api'", "'" + uiBase + "/api'")
                .replace("action=\"/pair\"", "action=\"" + uiBase + "/pair\"")
                .replace("action='/pair'", "action='" + uiBase + "/pair'")
                .replace("\"/pair\"", "\"" + uiBase + "/pair\"")
                .replace("\"/pair?", "\"" + uiBase + "/pair?")
                .replace("\"/pair/", "\"" + uiBase + "/pair/")
                .replace("'/pair'", "'" + uiBase + "/pair'")
                .replace("'/pair?", "'" + uiBase + "/pair?")
                .replace("'/pair/", "'" + uiBase + "/pair/");
        if (statusJson) {
            rewritten = STATUS_PAIRED_FALSE_PATTERN.matcher(rewritten).replaceFirst("\"paired\":true");
        }
        if (htmlContent) {
            rewritten = injectUiUrlShim(rewritten, uiBase, autoAuthQueryParam, authTokenQueryParam);
        }

        if (rewritten.equals(raw)) {
            return source;
        }
        return rewritten.getBytes(StandardCharsets.UTF_8);
    }

    private boolean isStatusEndpoint(String path) {
        if (!StringUtils.hasText(path)) {
            return false;
        }
        return "/api/status".equals(path)
                || "/api/status/".equals(path)
                || path.startsWith("/api/status/");
    }

    private boolean shouldSkipRequestHeader(String headerName) {
        return SKIPPED_REQUEST_HEADERS.contains(headerName.toLowerCase(Locale.ROOT));
    }

    private boolean shouldSkipResponseHeader(String headerName) {
        return SKIPPED_RESPONSE_HEADERS.contains(headerName.toLowerCase(Locale.ROOT));
    }

    private ArrayList<String> rewriteSetCookiePaths(java.util.List<String> sourceValues) {
        ArrayList<String> rewritten = new ArrayList<>();
        for (String raw : sourceValues) {
            if (!StringUtils.hasText(raw)) {
                rewritten.add(raw);
                continue;
            }
            if (raw.toLowerCase(Locale.ROOT).contains("path=")) {
                rewritten.add(raw.replaceAll("(?i)Path=[^;]*", "Path=/"));
            } else {
                rewritten.add(raw + "; Path=/");
            }
        }
        return rewritten;
    }

    private String normalizeScheme(String scheme) {
        if (!StringUtils.hasText(scheme)) {
            return "http";
        }
        String normalized = scheme.trim().toLowerCase();
        if (!normalized.equals("http") && !normalized.equals("https")) {
            throw new IllegalArgumentException("app.ui-controller.upstream-scheme must be http or https");
        }
        return normalized;
    }

    private String requireHost(String host) {
        if (!StringUtils.hasText(host)) {
            throw new IllegalArgumentException("app.ui-controller.upstream-host must not be blank");
        }
        return host.trim();
    }

    private String injectUiUrlShim(String html, String uiBase, String autoAuthParam, String authTokenParam) {
        if (!StringUtils.hasText(html) || html.contains("data-fun-claw-ui-shim")) {
            return html;
        }
        String escapedBase = uiBase
                .replace("\\", "\\\\")
                .replace("'", "\\'");
        String escapedAutoAuthParam = (autoAuthParam == null ? "autoAuth" : autoAuthParam)
                .replace("\\", "\\\\")
                .replace("'", "\\'");
        String escapedAuthTokenParam = (authTokenParam == null ? "authToken" : authTokenParam)
                .replace("\\", "\\\\")
                .replace("'", "\\'");
        String shim = """
                <script data-fun-claw-ui-shim>
                (function () {
                  var base = '%s';
                  var autoAuthParam = '%s';
                  var authTokenParam = '%s';
                  var doc = document;
                  function ensureCryptoRandomUUID() {
                    try {
                      var c = window.crypto || window.msCrypto;
                      if (!c) { return; }
                      if (typeof c.randomUUID === 'function') { return; }
                      if (typeof c.getRandomValues !== 'function') { return; }
                      c.randomUUID = function () {
                        var bytes = new Uint8Array(16);
                        c.getRandomValues(bytes);
                        bytes[6] = (bytes[6] & 0x0f) | 0x40;
                        bytes[8] = (bytes[8] & 0x3f) | 0x80;
                        var hex = [];
                        for (var i = 0; i < bytes.length; i++) {
                          var h = bytes[i].toString(16);
                          if (h.length < 2) { h = '0' + h; }
                          hex.push(h);
                        }
                        return hex[0] + hex[1] + hex[2] + hex[3] + '-'
                          + hex[4] + hex[5] + '-'
                          + hex[6] + hex[7] + '-'
                          + hex[8] + hex[9] + '-'
                          + hex[10] + hex[11] + hex[12] + hex[13] + hex[14] + hex[15];
                      };
                    } catch (e) {
                      // ignore polyfill errors
                    }
                  }
                  ensureCryptoRandomUUID();
                  function isTruthy(value) {
                    if (value === null || typeof value === 'undefined') { return false; }
                    var normalized = String(value).toLowerCase();
                    return normalized === '' || normalized === '1' || normalized === 'true' || normalized === 'yes' || normalized === 'on';
                  }
                  function tryApplyAutoAuthToken() {
                    if (!autoAuthParam || !authTokenParam) { return false; }
                    var params;
                    try {
                      params = new URLSearchParams(window.location.search);
                    } catch (e) {
                      return false;
                    }
                    if (!isTruthy(params.get(autoAuthParam))) { return false; }
                    var token = params.get(authTokenParam);
                    if (!token) { return false; }
                    try {
                      window.localStorage.setItem('zeroclaw_token', token);
                    } catch (e) {
                      return false;
                    }
                    params.delete(autoAuthParam);
                    params.delete(authTokenParam);
                    var query = params.toString();
                    var nextUrl = window.location.pathname + (query ? ('?' + query) : '') + (window.location.hash || '');
                    window.location.replace(nextUrl);
                    return true;
                  }
                  if (tryApplyAutoAuthToken()) { return; }
                  // Keep a placeholder token so UI won't force pairing screen.
                  // Real requests will strip token=public-access in rewriteUrlLike().
                  try {
                    var existingToken = window.localStorage.getItem('zeroclaw_token');
                    if (!existingToken) {
                      window.localStorage.setItem('zeroclaw_token', 'public-access');
                    }
                  } catch (e) {
                    // ignore storage errors and continue normal flow
                  }
                  function isHttpUrl(url) { return /^https?:\\/\\//i.test(url); }
                  function isWsUrl(url) { return /^wss?:\\/\\//i.test(url); }
                  function sanitizeTokenQuery(urlValue) {
                    if (typeof urlValue !== 'string' || !urlValue) { return urlValue; }
                    try {
                      var parsed = new URL(urlValue, window.location.origin);
                      var token = parsed.searchParams.get('token');
                      if (token !== 'public-access') { return urlValue; }
                      parsed.searchParams.delete('token');
                      var isRelative = !isHttpUrl(urlValue) && !isWsUrl(urlValue);
                      if (isRelative) {
                        return parsed.pathname + (parsed.search ? parsed.search : '') + (parsed.hash ? parsed.hash : '');
                      }
                      return parsed.toString();
                    } catch (e) {
                      return urlValue;
                    }
                  }
                  function prefixPath(path) {
                    if (typeof path !== 'string' || !path) { return path; }
                    if (path.indexOf(base + '/') === 0 || path === base) { return path; }
                    if (path.charAt(0) !== '/') { return path; }
                    if (path.indexOf('/fun-claw/api/') === 0) { return path; }
                    return base + path;
                  }
                  function rewriteUrlLike(url) {
                    if (typeof url === 'undefined' || url === null) { return url; }
                    if (typeof URL !== 'undefined' && url instanceof URL) {
                      return rewriteUrlLike(url.toString());
                    }
                    if (typeof url !== 'string' || !url) { return url; }
                    if (url.indexOf('//') === 0) { return url; }
                    if (url.charAt(0) === '#') { return url; }
                    if (isHttpUrl(url) || isWsUrl(url)) {
                      try {
                        var parsed = new URL(url, window.location.origin);
                        var sameOrigin = parsed.origin === window.location.origin;
                        var sameHost = parsed.host === window.location.host;
                        if (!sameOrigin && !(isWsUrl(url) && sameHost) && !(isHttpUrl(url) && sameHost)) { return url; }
                        var nextPath = prefixPath(parsed.pathname);
                        if (nextPath !== parsed.pathname) {
                          parsed.pathname = nextPath;
                        }
                        return sanitizeTokenQuery(parsed.toString());
                      } catch (e) {
                        return sanitizeTokenQuery(url);
                      }
                    }
                    return sanitizeTokenQuery(prefixPath(url));
                  }
                  var originalFetch = window.fetch;
                  if (typeof originalFetch === 'function') {
                    window.fetch = function (input, init) {
                      if (typeof input === 'string') {
                        return originalFetch.call(this, rewriteUrlLike(input), init);
                      }
                      if (typeof Request !== 'undefined' && input instanceof Request) {
                        var rewritten = rewriteUrlLike(input.url);
                        if (rewritten !== input.url) {
                          var copied = new Request(rewritten, input);
                          return originalFetch.call(this, copied, init);
                        }
                      }
                      return originalFetch.call(this, input, init);
                    };
                  }
                  var xhrOpen = window.XMLHttpRequest && window.XMLHttpRequest.prototype && window.XMLHttpRequest.prototype.open;
                  if (xhrOpen) {
                    window.XMLHttpRequest.prototype.open = function (method, url) {
                      var args = Array.prototype.slice.call(arguments);
                      args[1] = rewriteUrlLike(url);
                      return xhrOpen.apply(this, args);
                    };
                  }
                  var NativeWebSocket = window.WebSocket;
                  if (NativeWebSocket) {
                    var WrappedWebSocket = function (url, protocols) {
                      var rewritten = rewriteUrlLike(url);
                      return protocols === undefined ? new NativeWebSocket(rewritten) : new NativeWebSocket(rewritten, protocols);
                    };
                    WrappedWebSocket.prototype = NativeWebSocket.prototype;
                    // Preserve static constants so app readiness checks still work.
                    WrappedWebSocket.CONNECTING = NativeWebSocket.CONNECTING;
                    WrappedWebSocket.OPEN = NativeWebSocket.OPEN;
                    WrappedWebSocket.CLOSING = NativeWebSocket.CLOSING;
                    WrappedWebSocket.CLOSED = NativeWebSocket.CLOSED;
                    if (typeof NativeWebSocket === 'function') {
                      try {
                        Object.getOwnPropertyNames(NativeWebSocket).forEach(function (key) {
                          if (key === 'prototype' || key === 'length' || key === 'name') { return; }
                          try {
                            WrappedWebSocket[key] = NativeWebSocket[key];
                          } catch (e) {
                            // ignore read-only static props
                          }
                        });
                      } catch (e) {
                        // ignore reflection errors
                      }
                    }
                    window.WebSocket = WrappedWebSocket;
                  }
                  var NativeEventSource = window.EventSource;
                  if (NativeEventSource) {
                    window.EventSource = function (url, configuration) {
                      return new NativeEventSource(rewriteUrlLike(url), configuration);
                    };
                    window.EventSource.prototype = NativeEventSource.prototype;
                  }
                  function rewriteForms(root) {
                    if (!root || !root.querySelectorAll) { return; }
                    var forms = root.querySelectorAll('form[action]');
                    for (var i = 0; i < forms.length; i++) {
                      var current = forms[i].getAttribute('action');
                      var next = rewriteUrlLike(current);
                      if (next !== current) {
                        forms[i].setAttribute('action', next);
                      }
                    }
                  }
                  function rewriteAnchors(root) {
                    if (!root || !root.querySelectorAll) { return; }
                    var anchors = root.querySelectorAll('a[href]');
                    for (var i = 0; i < anchors.length; i++) {
                      var current = anchors[i].getAttribute('href');
                      var next = rewriteUrlLike(current);
                      if (next !== current) {
                        anchors[i].setAttribute('href', next);
                      }
                    }
                  }
                  function rewriteDomTargets(root) {
                    rewriteForms(root);
                    rewriteAnchors(root);
                  }
                  if (doc.readyState === 'loading') {
                    doc.addEventListener('DOMContentLoaded', function () {
                      rewriteDomTargets(doc);
                    });
                  } else {
                    rewriteDomTargets(doc);
                  }
                  if (window.MutationObserver && doc.documentElement) {
                    var observer = new MutationObserver(function (mutations) {
                      for (var i = 0; i < mutations.length; i++) {
                        var addedNodes = mutations[i].addedNodes;
                        for (var j = 0; j < addedNodes.length; j++) {
                          var node = addedNodes[j];
                          if (node && node.nodeType === 1) {
                            rewriteDomTargets(node);
                          }
                        }
                      }
                    });
                    observer.observe(doc.documentElement, { childList: true, subtree: true });
                  }
                })();
                </script>
                """.formatted(escapedBase, escapedAutoAuthParam, escapedAuthTokenParam);
        String normalized = html;
        int headClose = normalized.toLowerCase(Locale.ROOT).indexOf("</head>");
        if (headClose >= 0) {
            return normalized.substring(0, headClose) + shim + normalized.substring(headClose);
        }
        return shim + normalized;
    }
}
