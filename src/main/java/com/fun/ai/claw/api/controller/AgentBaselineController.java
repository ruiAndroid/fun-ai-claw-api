package com.fun.ai.claw.api.controller;

import com.fun.ai.claw.api.model.AgentBaselineResponse;
import com.fun.ai.claw.api.model.AgentBaselineSummaryResponse;
import com.fun.ai.claw.api.model.AgentBaselineUpsertRequest;
import com.fun.ai.claw.api.model.ListResponse;
import com.fun.ai.claw.api.service.AgentBaselineService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/agent-baselines")
public class AgentBaselineController {

    private final AgentBaselineService agentBaselineService;

    public AgentBaselineController(AgentBaselineService agentBaselineService) {
        this.agentBaselineService = agentBaselineService;
    }

    @GetMapping
    public ListResponse<AgentBaselineSummaryResponse> listBaselines() {
        return agentBaselineService.listBaselines();
    }

    @GetMapping("/{agentKey}")
    public AgentBaselineResponse getBaseline(@PathVariable String agentKey) {
        return agentBaselineService.getBaseline(agentKey);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AgentBaselineResponse createBaseline(@RequestBody AgentBaselineUpsertRequest request) {
        return agentBaselineService.createBaseline(request);
    }

    @PutMapping("/{agentKey}")
    public AgentBaselineResponse upsertBaseline(@PathVariable String agentKey,
                                                @RequestBody AgentBaselineUpsertRequest request) {
        return agentBaselineService.upsertBaseline(agentKey, request);
    }

    @DeleteMapping("/{agentKey}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteBaseline(@PathVariable String agentKey) {
        agentBaselineService.deleteBaseline(agentKey);
    }
}
