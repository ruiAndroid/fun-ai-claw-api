package com.fun.ai.claw.api.controller;

import com.fun.ai.claw.api.model.AgentTaskConfirmRequest;
import com.fun.ai.claw.api.model.AgentTaskConfirmResponse;
import com.fun.ai.claw.api.model.AgentTaskPrepareRequest;
import com.fun.ai.claw.api.model.AgentTaskPrepareResponse;
import com.fun.ai.claw.api.model.AgentTaskResponse;
import com.fun.ai.claw.api.service.AgentTaskService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/agent-tasks")
public class AgentTaskController {

    private final AgentTaskService service;

    public AgentTaskController(AgentTaskService service) {
        this.service = service;
    }

    @PostMapping("/prepare")
    public AgentTaskPrepareResponse prepare(@Valid @RequestBody AgentTaskPrepareRequest request) {
        return service.prepare(request);
    }

    @PostMapping("/confirm")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AgentTaskConfirmResponse confirm(@Valid @RequestBody AgentTaskConfirmRequest request) {
        return service.confirm(request.confirmToken());
    }

    @GetMapping("/tasks/{taskId}")
    public AgentTaskResponse getTask(@PathVariable UUID taskId) {
        return service.getTask(taskId);
    }
}

