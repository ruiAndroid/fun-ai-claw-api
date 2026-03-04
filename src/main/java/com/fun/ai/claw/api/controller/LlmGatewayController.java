package com.fun.ai.claw.api.controller;

import com.fun.ai.claw.api.service.LlmGatewayService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/v1", "/api/v1"})
public class LlmGatewayController {

    private final LlmGatewayService llmGatewayService;

    public LlmGatewayController(LlmGatewayService llmGatewayService) {
        this.llmGatewayService = llmGatewayService;
    }

    @GetMapping("/models")
    public ResponseEntity<String> models(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return toResponse(llmGatewayService.getModels(authorization));
    }

    @PostMapping("/chat/completions")
    public ResponseEntity<String> chatCompletions(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody(required = false) String requestBody) {
        return toResponse(llmGatewayService.chatCompletions(authorization, requestBody));
    }

    @PostMapping("/messages")
    public ResponseEntity<String> messages(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody(required = false) String requestBody) {
        return toResponse(llmGatewayService.messages(authorization, requestBody));
    }

    private ResponseEntity<String> toResponse(LlmGatewayService.ProxyResponse upstream) {
        MediaType contentType = MediaType.APPLICATION_JSON;
        try {
            contentType = MediaType.parseMediaType(upstream.contentType());
        } catch (Exception ignored) {
            // Keep default JSON media type when upstream content-type is invalid.
        }
        return ResponseEntity.status(upstream.statusCode())
                .contentType(contentType)
                .body(upstream.body() == null ? "" : upstream.body());
    }
}

