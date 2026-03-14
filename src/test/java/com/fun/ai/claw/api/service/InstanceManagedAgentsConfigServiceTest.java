package com.fun.ai.claw.api.service;

import com.fun.ai.claw.api.model.InstanceAgentBindingRecord;
import com.fun.ai.claw.api.repository.InstanceAgentBindingRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InstanceManagedAgentsConfigServiceTest {

    @Test
    void applyPolicyFallsBackToDefaultProviderAndModel() {
        UUID instanceId = UUID.randomUUID();
        InstanceAgentBindingRepository repository = mock(InstanceAgentBindingRepository.class);
        when(repository.findByInstanceId(instanceId)).thenReturn(List.of(
                new InstanceAgentBindingRecord(
                        instanceId,
                        "mgc-novel-to-script2",
                        null,
                        null,
                        0.3D,
                        true,
                        "test prompt",
                        List.of("delegate"),
                        List.of("skill-novel-to-script"),
                        null,
                        "tester",
                        null,
                        null
                )
        ));

        InstanceManagedAgentsConfigService service = new InstanceManagedAgentsConfigService(repository);
        String rendered = service.applyPolicy(instanceId, """
                default_provider = "custom:https://api.ai.fun.tv/v1"
                default_model = "MiniMax-M2.5"

                [hooks]
                enabled = true
                """);

        assertTrue(rendered.contains("[agents.\"mgc-novel-to-script2\"]"));
        assertTrue(rendered.contains("provider = \"custom:https://api.ai.fun.tv/v1\""));
        assertTrue(rendered.contains("model = \"MiniMax-M2.5\""));
        assertTrue(rendered.contains("allowed_skills"));
        assertTrue(rendered.contains("skill-novel-to-script"));
    }

    @Test
    void parseBindingsFallsBackToDefaultProviderAndModel() {
        UUID instanceId = UUID.randomUUID();
        InstanceAgentBindingRepository repository = mock(InstanceAgentBindingRepository.class);
        InstanceManagedAgentsConfigService service = new InstanceManagedAgentsConfigService(repository);

        List<InstanceAgentBindingRecord> parsed = service.parseBindings(instanceId, """
                default_provider = "custom:https://api.ai.fun.tv/v1"
                default_model = "MiniMax-M2.5"

                [agents."mgc-novel-to-script2"]
                system_prompt = "test prompt"
                agentic = true
                allowed_skills = ["skill-novel-to-script"]
                """);

        assertEquals(1, parsed.size());
        assertEquals("custom:https://api.ai.fun.tv/v1", parsed.get(0).provider());
        assertEquals("MiniMax-M2.5", parsed.get(0).model());
        assertEquals(List.of("skill-novel-to-script"), parsed.get(0).allowedSkills());
    }
}
