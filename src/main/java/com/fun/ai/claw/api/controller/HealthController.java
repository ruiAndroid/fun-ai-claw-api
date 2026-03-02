package com.fun.ai.claw.api.controller;

import com.fun.ai.claw.api.model.HealthResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
public class HealthController {

    @GetMapping("/health")
    public HealthResponse getHealth() {
        return new HealthResponse("UP", "fun-ai-claw-api");
    }
}

