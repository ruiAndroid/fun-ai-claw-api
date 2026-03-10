package com.fun.ai.claw.api.controller;

import com.fun.ai.claw.api.model.ListResponse;
import com.fun.ai.claw.api.model.OpenClientAppRecord;
import com.fun.ai.claw.api.model.OpenSessionCreateRequest;
import com.fun.ai.claw.api.model.OpenSessionMessageResponse;
import com.fun.ai.claw.api.model.OpenSessionResponse;
import com.fun.ai.claw.api.service.OpenApiAuthService;
import com.fun.ai.claw.api.service.OpenSessionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/open/v1")
public class OpenSessionController {

    private final OpenApiAuthService openApiAuthService;
    private final OpenSessionService openSessionService;

    public OpenSessionController(OpenApiAuthService openApiAuthService,
                                 OpenSessionService openSessionService) {
        this.openApiAuthService = openApiAuthService;
        this.openSessionService = openSessionService;
    }

    @PostMapping("/sessions")
    public OpenSessionResponse createSession(@RequestHeader HttpHeaders headers,
                                             @Valid @RequestBody(required = false) OpenSessionCreateRequest request) {
        OpenClientAppRecord app = openApiAuthService.authenticate(headers);
        return openSessionService.createSession(app, request);
    }

    @GetMapping("/sessions/{sessionId}")
    public OpenSessionResponse getSession(@RequestHeader HttpHeaders headers,
                                          @PathVariable UUID sessionId) {
        OpenClientAppRecord app = openApiAuthService.authenticate(headers);
        return openSessionService.getSession(app, sessionId);
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public ListResponse<OpenSessionMessageResponse> listMessages(@RequestHeader HttpHeaders headers,
                                                                 @PathVariable UUID sessionId,
                                                                 @RequestParam(name = "limit", required = false) Integer limit) {
        OpenClientAppRecord app = openApiAuthService.authenticate(headers);
        return new ListResponse<>(openSessionService.listMessages(app, sessionId, limit));
    }

    @PostMapping("/sessions/{sessionId}/close")
    public OpenSessionResponse closeSession(@RequestHeader HttpHeaders headers,
                                            @PathVariable UUID sessionId) {
        OpenClientAppRecord app = openApiAuthService.authenticate(headers);
        return openSessionService.closeSession(app, sessionId);
    }
}
