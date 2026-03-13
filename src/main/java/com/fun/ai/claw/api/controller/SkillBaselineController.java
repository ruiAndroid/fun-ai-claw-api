package com.fun.ai.claw.api.controller;

import com.fun.ai.claw.api.model.ListResponse;
import com.fun.ai.claw.api.model.SkillBaselineResponse;
import com.fun.ai.claw.api.model.SkillBaselineSummaryResponse;
import com.fun.ai.claw.api.model.SkillBaselineUpsertRequest;
import com.fun.ai.claw.api.service.SkillBaselineService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/v1/skill-baselines")
public class SkillBaselineController {

    private final SkillBaselineService skillBaselineService;

    public SkillBaselineController(SkillBaselineService skillBaselineService) {
        this.skillBaselineService = skillBaselineService;
    }

    @GetMapping
    public ListResponse<SkillBaselineSummaryResponse> listBaselines() {
        return skillBaselineService.listBaselines();
    }

    @GetMapping("/{skillKey}")
    public SkillBaselineResponse getBaseline(@PathVariable String skillKey) {
        return skillBaselineService.getBaseline(skillKey);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SkillBaselineResponse createBaseline(@RequestBody SkillBaselineUpsertRequest request) {
        return skillBaselineService.createBaseline(request);
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public SkillBaselineResponse uploadBaseline(@RequestParam String skillKey,
                                                @RequestParam(required = false) String displayName,
                                                @RequestParam(required = false) String description,
                                                @RequestParam(required = false) Boolean enabled,
                                                @RequestParam(required = false) String updatedBy,
                                                @RequestParam("file") MultipartFile file) throws java.io.IOException {
        return skillBaselineService.uploadAndCreateBaseline(
                skillKey,
                displayName,
                description,
                enabled,
                updatedBy,
                file == null ? null : file.getBytes()
        );
    }

    @PutMapping("/{skillKey}")
    public SkillBaselineResponse upsertBaseline(@PathVariable String skillKey,
                                                @RequestBody SkillBaselineUpsertRequest request) {
        return skillBaselineService.upsertBaseline(skillKey, request);
    }

    @DeleteMapping("/{skillKey}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteBaseline(@PathVariable String skillKey) {
        skillBaselineService.deleteBaseline(skillKey);
    }
}
