package com.fun.ai.claw.api.service;

import com.fun.ai.claw.api.config.LlmGatewayProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

@Service
public class LlmGatewayService {

    private final LlmGatewayProperties properties;
    private final HttpClient httpClient;

    public LlmGatewayService(LlmGatewayProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder().build();
    }

    public ProxyResponse getModels(String incomingAuthorization) {
        return proxy("GET", "/models", incomingAuthorization, null);
    }

    public ProxyResponse chatCompletions(String incomingAuthorization, String requestBody) {
        return proxy("POST", "/chat/completions", incomingAuthorization, requestBody);
    }

    public ProxyResponse messages(String incomingAuthorization, String requestBody) {
        return proxy("POST", "/messages", incomingAuthorization, requestBody);
    }

    private ProxyResponse proxy(String method, String path, String incomingAuthorization, String requestBody) {
        URI endpoint = buildEndpoint(path);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(endpoint)
                .timeout(Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds())));

        String authHeader = resolveAuthorization(incomingAuthorization);
        if (StringUtils.hasText(authHeader)) {
            builder.header(HttpHeaders.AUTHORIZATION, authHeader);
        }

        if ("POST".equalsIgnoreCase(method)) {
            builder.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
            builder.header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
            String body = requestBody == null ? "" : requestBody;
            builder.POST(HttpRequest.BodyPublishers.ofString(body));
        } else {
            builder.GET();
        }

        HttpResponse<String> upstream;
        try {
            upstream = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "llm gateway io error: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "llm gateway interrupted");
        }

        String contentType = upstream.headers()
                .firstValue(HttpHeaders.CONTENT_TYPE)
                .orElse(MediaType.APPLICATION_JSON_VALUE);
        return new ProxyResponse(upstream.statusCode(), upstream.body(), contentType);
    }

    private URI buildEndpoint(String path) {
        String baseUrl = properties.getBaseUrl();
        if (!StringUtils.hasText(baseUrl)) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "app.llm-gateway.base-url is empty");
        }
        String normalized = baseUrl.trim();
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        return URI.create(normalized + normalizedPath);
    }

    private String resolveAuthorization(String incomingAuthorization) {
        if (properties.isPreferIncomingAuthorization() && StringUtils.hasText(incomingAuthorization)) {
            return incomingAuthorization.trim();
        }
        String token = properties.getAuthToken();
        if (!StringUtils.hasText(token)) {
            return null;
        }
        String scheme = properties.getAuthScheme();
        String normalizedScheme = StringUtils.hasText(scheme) ? scheme.trim() : "Bearer";
        if (normalizedScheme.toLowerCase(Locale.ROOT).endsWith(" ")) {
            normalizedScheme = normalizedScheme.trim();
        }
        return normalizedScheme + " " + token.trim();
    }

    public record ProxyResponse(int statusCode, String body, String contentType) {
    }
}

