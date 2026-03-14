package com.fun.ai.claw.api.service;

import com.fun.ai.claw.api.model.AgentBaselineRecord;
import com.fun.ai.claw.api.model.AgentBaselineUpsertRequest;
import com.fun.ai.claw.api.repository.AgentBaselineRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentBaselineServiceTest {

    @Test
    void createBaselineMapsLegacyAllowedToolsIntoExtraTools() {
        AgentBaselineRepository repository = mock(AgentBaselineRepository.class);
        AgentToolCatalogService toolCatalogService = new AgentToolCatalogService();
        AgentBaselineService service = new AgentBaselineService(repository, toolCatalogService);

        when(repository.existsByAgentKey("writer")).thenReturn(false);
        when(repository.findByAgentKey("writer"))
                .thenReturn(Optional.empty())
                .thenAnswer(invocation -> {
                    Instant now = Instant.now();
                    return Optional.of(new AgentBaselineRecord(
                            "writer",
                            "Writer",
                            null,
                            "zeroclaw",
                            "MANUAL",
                            null,
                            true,
                            null,
                            null,
                            null,
                            true,
                            null,
                            List.of("file_read", "file_write"),
                            List.of(),
                            List.of("file_read", "file_write"),
                            null,
                            "tester",
                            now,
                            now
                    ));
                });

        service.createBaseline(new AgentBaselineUpsertRequest(
                "writer",
                "Writer",
                null,
                "zeroclaw",
                "MANUAL",
                null,
                true,
                null,
                null,
                null,
                true,
                null,
                null,
                null,
                List.of("file_read", "file_write"),
                null,
                "tester"
        ));

        ArgumentCaptor<AgentBaselineRecord> captor = ArgumentCaptor.forClass(AgentBaselineRecord.class);
        verify(repository, times(1)).upsert(captor.capture(), any());

        assertEquals(null, captor.getValue().toolPresetKey());
        assertEquals(List.of("file_read", "file_write"), captor.getValue().allowedToolsExtra());
        assertEquals(List.of(), captor.getValue().deniedTools());
        assertEquals(List.of("file_read", "file_write"), captor.getValue().allowedTools());
    }

    @Test
    void upsertBaselineResolvesPresetIntoAllowedTools() {
        AgentBaselineRepository repository = mock(AgentBaselineRepository.class);
        AgentToolCatalogService toolCatalogService = new AgentToolCatalogService();
        AgentBaselineService service = new AgentBaselineService(repository, toolCatalogService);

        when(repository.findByAgentKey("writer")).thenReturn(Optional.empty(), Optional.of(new AgentBaselineRecord(
                "writer",
                "Writer",
                null,
                "zeroclaw",
                "MANUAL",
                null,
                true,
                null,
                null,
                null,
                true,
                "writer_basic",
                List.of("file_edit"),
                List.of("content_search"),
                List.of("file_read", "glob_search", "file_write", "file_edit"),
                null,
                "tester",
                Instant.now(),
                Instant.now()
        )));

        service.upsertBaseline("writer", new AgentBaselineUpsertRequest(
                "writer",
                "Writer",
                null,
                "zeroclaw",
                "MANUAL",
                null,
                true,
                null,
                null,
                null,
                true,
                "writer_basic",
                List.of("file_edit"),
                List.of("content_search"),
                null,
                null,
                "tester"
        ));

        ArgumentCaptor<AgentBaselineRecord> captor = ArgumentCaptor.forClass(AgentBaselineRecord.class);
        verify(repository, times(1)).upsert(captor.capture(), any());
        assertEquals("writer_basic", captor.getValue().toolPresetKey());
        assertEquals(List.of("file_edit"), captor.getValue().allowedToolsExtra());
        assertEquals(List.of("content_search"), captor.getValue().deniedTools());
        assertEquals(List.of("file_read", "glob_search", "file_write", "file_edit"), captor.getValue().allowedTools());
    }
}
