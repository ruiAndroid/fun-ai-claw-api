package com.fun.ai.claw.api.controller;

import com.fun.ai.claw.api.model.AgentToolCatalogResponse;
import com.fun.ai.claw.api.service.AgentToolCatalogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/agent-tools")
public class AgentToolCatalogController {

    private final AgentToolCatalogService agentToolCatalogService;

    public AgentToolCatalogController(AgentToolCatalogService agentToolCatalogService) {
        this.agentToolCatalogService = agentToolCatalogService;
    }

    @GetMapping("/catalog")
    public AgentToolCatalogResponse getCatalog() {
        return agentToolCatalogService.getCatalog();
    }
}
