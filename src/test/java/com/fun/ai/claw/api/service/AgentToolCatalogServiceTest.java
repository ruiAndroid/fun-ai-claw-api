package com.fun.ai.claw.api.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentToolCatalogServiceTest {

    private final AgentToolCatalogService service = new AgentToolCatalogService();

    @Test
    void resolveToolsCombinesPresetExtraAndDenied() {
        List<String> resolved = service.resolveTools(
                "writer_basic",
                List.of("file_edit", "file_read"),
                List.of("content_search")
        );

        assertEquals(List.of("file_read", "glob_search", "file_write", "file_edit"), resolved);
    }

    @Test
    void normalizePresetKeyRejectsUnknownPreset() {
        assertThrows(ResponseStatusException.class, () -> service.normalizePresetKey("missing"));
    }
}
