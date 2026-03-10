package com.fun.ai.claw.api.controller;

import com.fun.ai.claw.api.model.ListResponse;
import com.fun.ai.claw.api.model.OpenClientAppCreateRequest;
import com.fun.ai.claw.api.model.OpenClientAppCreateResponse;
import com.fun.ai.claw.api.model.OpenClientAppResponse;
import com.fun.ai.claw.api.model.OpenClientAppUpdateRequest;
import com.fun.ai.claw.api.service.OpenClientAppService;
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
@RequestMapping("/v1/open-apps")
public class OpenClientAppController {

    private final OpenClientAppService openClientAppService;

    public OpenClientAppController(OpenClientAppService openClientAppService) {
        this.openClientAppService = openClientAppService;
    }

    @GetMapping
    public ListResponse<OpenClientAppResponse> listApps() {
        return openClientAppService.listApps();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OpenClientAppCreateResponse createApp(@RequestBody OpenClientAppCreateRequest request) {
        return openClientAppService.createApp(request);
    }

    @PutMapping("/{appId}")
    public OpenClientAppResponse updateApp(@PathVariable String appId,
                                           @RequestBody OpenClientAppUpdateRequest request) {
        return openClientAppService.updateApp(appId, request);
    }

    @DeleteMapping("/{appId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteApp(@PathVariable String appId) {
        openClientAppService.deleteApp(appId);
    }
}
