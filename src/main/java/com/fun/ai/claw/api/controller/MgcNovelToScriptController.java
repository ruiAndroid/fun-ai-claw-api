package com.fun.ai.claw.api.controller;

import com.fun.ai.claw.api.model.MgcNovelToScriptConfirmRequest;
import com.fun.ai.claw.api.model.MgcNovelToScriptConfirmResponse;
import com.fun.ai.claw.api.model.MgcNovelToScriptPrepareRequest;
import com.fun.ai.claw.api.model.MgcNovelToScriptPrepareResponse;
import com.fun.ai.claw.api.model.MgcNovelToScriptTaskResponse;
import com.fun.ai.claw.api.service.MgcNovelToScriptService;
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
@RequestMapping("/v1/mgc-novel-to-script")
public class MgcNovelToScriptController {

    private final MgcNovelToScriptService service;

    public MgcNovelToScriptController(MgcNovelToScriptService service) {
        this.service = service;
    }

    @PostMapping("/prepare")
    public MgcNovelToScriptPrepareResponse prepare(@Valid @RequestBody MgcNovelToScriptPrepareRequest request) {
        return service.prepare(request);
    }

    @PostMapping("/confirm")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MgcNovelToScriptConfirmResponse confirm(@Valid @RequestBody MgcNovelToScriptConfirmRequest request) {
        return service.confirm(request.confirmToken());
    }

    @GetMapping("/tasks/{taskId}")
    public MgcNovelToScriptTaskResponse getTask(@PathVariable UUID taskId) {
        return service.getTask(taskId);
    }
}

