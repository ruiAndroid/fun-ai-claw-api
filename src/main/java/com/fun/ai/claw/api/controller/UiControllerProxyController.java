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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@RestController
public class UiControllerProxyController {

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

    public UiControllerProxyController(InstanceRepository instanceRepository,
                                       @Value("${app.ui-controller.upstream-scheme:http}") String upstreamScheme,
                                       @Value("${app.ui-controller.upstream-host:127.0.0.1}") String upstreamHost,
                                       @Value("${app.ui-controller.request-timeout-seconds:60}") long requestTimeoutSeconds) {
        this.instanceRepository = instanceRepository;
        this.upstreamScheme = normalizeScheme(upstreamScheme);
        this.upstreamHost = requireHost(upstreamHost);
        long timeoutSeconds = requestTimeoutSeconds > 0 ? requestTimeoutSeconds : 60;
        this.requestTimeout = Duration.ofSeconds(timeoutSeconds);
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
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
            }
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
        HttpRequest outboundRequest = buildOutboundRequest(request, requestBody, targetUri);
        HttpResponse<byte[]> upstreamResponse = send(outboundRequest);
        return buildResponse(instanceId, upstreamResponse);
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

        StringBuilder uriBuilder = new StringBuilder()
                .append(upstreamScheme)
                .append("://")
                .append(upstreamHost)
                .append(":")
                .append(targetPort)
                .append(downstreamPath);
        if (StringUtils.hasText(request.getQueryString())) {
            uriBuilder.append("?").append(request.getQueryString());
        }
        return URI.create(uriBuilder.toString());
    }

    private HttpRequest buildOutboundRequest(HttpServletRequest inboundRequest, byte[] requestBody, URI targetUri) {
        HttpRequest.BodyPublisher bodyPublisher = (requestBody == null || requestBody.length == 0)
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(requestBody);

        HttpRequest.Builder outboundBuilder = HttpRequest.newBuilder()
                .uri(targetUri)
                .timeout(requestTimeout)
                .method(inboundRequest.getMethod(), bodyPublisher);

        copyRequestHeaders(inboundRequest, outboundBuilder);
        return outboundBuilder.build();
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
        try {
            return httpClient.send(outboundRequest, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "failed to proxy ui request: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "ui proxy request interrupted");
        }
    }

    private ResponseEntity<byte[]> buildResponse(UUID instanceId, HttpResponse<byte[]> upstreamResponse) {
        byte[] responseBody = rewriteUiAssetRootPath(instanceId, upstreamResponse);
        HttpHeaders responseHeaders = new HttpHeaders();
        upstreamResponse.headers().map().forEach((headerName, values) -> {
            if (shouldSkipResponseHeader(headerName)) {
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

    private byte[] rewriteUiAssetRootPath(UUID instanceId, HttpResponse<byte[]> upstreamResponse) {
        String contentType = upstreamResponse.headers()
                .firstValue("content-type")
                .orElse("")
                .toLowerCase(Locale.ROOT);

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
                .replace("action=\"/pair\"", "action=\"" + uiBase + "/pair\"")
                .replace("action='/pair'", "action='" + uiBase + "/pair'")
                .replace("\"/pair\"", "\"" + uiBase + "/pair\"")
                .replace("\"/pair?", "\"" + uiBase + "/pair?")
                .replace("\"/pair/", "\"" + uiBase + "/pair/")
                .replace("'/pair'", "'" + uiBase + "/pair'")
                .replace("'/pair?", "'" + uiBase + "/pair?")
                .replace("'/pair/", "'" + uiBase + "/pair/");

        if (rewritten.equals(raw)) {
            return source;
        }
        return rewritten.getBytes(StandardCharsets.UTF_8);
    }

    private boolean shouldSkipRequestHeader(String headerName) {
        return SKIPPED_REQUEST_HEADERS.contains(headerName.toLowerCase(Locale.ROOT));
    }

    private boolean shouldSkipResponseHeader(String headerName) {
        return SKIPPED_RESPONSE_HEADERS.contains(headerName.toLowerCase(Locale.ROOT));
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
}
