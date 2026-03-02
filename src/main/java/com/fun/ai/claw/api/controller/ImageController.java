package com.fun.ai.claw.api.controller;

import com.fun.ai.claw.api.model.ImagePresetDto;
import com.fun.ai.claw.api.model.ListResponse;
import com.fun.ai.claw.api.service.ControlService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/images")
public class ImageController {

    private final ControlService controlService;

    public ImageController(ControlService controlService) {
        this.controlService = controlService;
    }

    @GetMapping
    public ListResponse<ImagePresetDto> listImages() {
        return new ListResponse<>(controlService.listImagePresets());
    }
}

