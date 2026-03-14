package com.fun.ai.claw.api.service;

import com.fun.ai.claw.api.model.AgentBaselineRecord;
import com.fun.ai.claw.api.model.ClawInstanceDto;
import com.fun.ai.claw.api.model.InstanceConfigResponse;
import com.fun.ai.claw.api.model.InstanceDesiredState;
import com.fun.ai.claw.api.model.InstanceRuntime;
import com.fun.ai.claw.api.model.InstanceStatus;
import com.fun.ai.claw.api.model.UpsertInstanceAgentBindingRequest;
import com.fun.ai.claw.api.repository.AgentBaselineRepository;
import com.fun.ai.claw.api.repository.InstanceAgentBindingRepository;
import com.fun.ai.claw.api.repository.InstanceRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InstanceAgentBindingServiceTest {

    @Test
    void upsertInheritsAgenticAndAllowedToolsFromBaselineWhenInstalling() {
        UUID instanceId = UUID.randomUUID();
        Instant now = Instant.now();

        InstanceRepository instanceRepository = mock(InstanceRepository.class);
        AgentBaselineRepository agentBaselineRepository = mock(AgentBaselineRepository.class);
        InstanceAgentBindingRepository bindingRepository = mock(InstanceAgentBindingRepository.class);
        InstanceManagedAgentsConfigService managedAgentsConfigService = mock(InstanceManagedAgentsConfigService.class);
        InstanceConfigService instanceConfigService = mock(InstanceConfigService.class);
        InstanceConfigMutationService instanceConfigMutationService = mock(InstanceConfigMutationService.class);

        when(instanceRepository.findById(instanceId)).thenReturn(Optional.of(new ClawInstanceDto(
                instanceId,
                "demo",
                UUID.randomUUID(),
                "zeroclaw-shell:latest",
                null,
                null,
                null,
                InstanceRuntime.ZEROCLAW,
                InstanceStatus.RUNNING,
                InstanceDesiredState.RUNNING,
                now,
                now
        )));

        AgentBaselineRecord baseline = new AgentBaselineRecord(
                "mgc-novel-to-script2",
                "mgc-novel-to-script2",
                "story writer",
                "zeroclaw",
                "MANUAL",
                null,
                true,
                "custom:https://api.ai.fun.tv/v1",
                "MiniMax-M2.5",
                0.3D,
                true,
                null,
                List.of("file_read"),
                List.of(),
                List.of("file_read"),
                "prompt",
                "tester",
                now,
                now
        );
        when(agentBaselineRepository.findByAgentKey("mgc-novel-to-script2")).thenReturn(Optional.of(baseline));

        when(bindingRepository.findByInstanceId(instanceId))
                .thenReturn(List.of(), List.of(), List.of(
                        new com.fun.ai.claw.api.model.InstanceAgentBindingRecord(
                                instanceId,
                                "mgc-novel-to-script2",
                                "custom:https://api.ai.fun.tv/v1",
                                "MiniMax-M2.5",
                                0.3D,
                                true,
                                "prompt",
                                List.of("file_read"),
                                null,
                                "ui-dashboard",
                                now,
                                now
                        )
                ));

        InstanceConfigResponse configResponse = new InstanceConfigResponse(
                instanceId,
                "/tmp/config.toml",
                "runtime",
                "default_provider = \"custom:https://api.ai.fun.tv/v1\"\n",
                false,
                false,
                null,
                now,
                "tester"
        );
        when(instanceConfigService.get(instanceId)).thenReturn(configResponse);
        when(managedAgentsConfigService.parseBindings(eq(instanceId), any())).thenReturn(List.of());
        when(managedAgentsConfigService.extractDefaults(any()))
                .thenReturn(new InstanceManagedAgentsConfigService.ManagedAgentDefaults(
                        "custom:https://api.ai.fun.tv/v1",
                        "MiniMax-M2.5"
                ));

        InstanceAgentBindingService service = new InstanceAgentBindingService(
                instanceRepository,
                agentBaselineRepository,
                bindingRepository,
                managedAgentsConfigService,
                instanceConfigService,
                instanceConfigMutationService
        );

        var response = service.upsert(
                instanceId,
                "mgc-novel-to-script2",
                new UpsertInstanceAgentBindingRequest(null, null, null, null, null, null, "ui-dashboard")
        );

        ArgumentCaptor<com.fun.ai.claw.api.model.InstanceAgentBindingRecord> captor =
                ArgumentCaptor.forClass(com.fun.ai.claw.api.model.InstanceAgentBindingRecord.class);
        verify(bindingRepository, times(1)).upsert(captor.capture(), any());

        assertTrue(Boolean.TRUE.equals(captor.getValue().agentic()));
        assertEquals(List.of("file_read"), captor.getValue().allowedTools());
        assertTrue(Boolean.TRUE.equals(response.agentic()));
        assertEquals(List.of("file_read"), response.allowedTools());
    }
}
