package com.fun.ai.claw.api.service;

import com.fun.ai.claw.api.repository.InstanceSkillBindingRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InstanceManagedSkillsConfigServiceTest {

    @Test
    void applyPolicyPinsManagedOpenSkillsDirInsideWorkspace() {
        UUID instanceId = UUID.randomUUID();
        InstanceSkillBindingRepository repository = mock(InstanceSkillBindingRepository.class);
        when(repository.findByInstanceId(instanceId)).thenReturn(List.of());

        InstanceManagedSkillsConfigService service = new InstanceManagedSkillsConfigService(repository);
        String rendered = service.applyPolicy(instanceId, """
                [skills]
                open_skills_enabled = false
                open_skills_dir = "/workspace/open-skills"
                """);

        assertTrue(rendered.contains("open_skills_enabled = true"));
        assertTrue(rendered.contains("open_skills_dir = \"/zeroclaw-data/workspace/open-skills\""));
    }
}
