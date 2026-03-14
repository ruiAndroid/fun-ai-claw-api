package com.fun.ai.claw.api.service;

import com.fun.ai.claw.api.model.AgentToolCatalogResponse;
import com.fun.ai.claw.api.model.AgentToolDefinitionResponse;
import com.fun.ai.claw.api.model.AgentToolPresetResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AgentToolCatalogService {

    private final List<AgentToolDefinitionResponse> tools = List.of(
            new AgentToolDefinitionResponse("shell", "Run shell commands"),
            new AgentToolDefinitionResponse("file_read", "Read files"),
            new AgentToolDefinitionResponse("file_write", "Write files"),
            new AgentToolDefinitionResponse("file_edit", "Edit files"),
            new AgentToolDefinitionResponse("glob_search", "Search file paths"),
            new AgentToolDefinitionResponse("content_search", "Search file contents"),
            new AgentToolDefinitionResponse("cron_add", "Create cron jobs"),
            new AgentToolDefinitionResponse("cron_list", "List cron jobs"),
            new AgentToolDefinitionResponse("cron_remove", "Remove cron jobs"),
            new AgentToolDefinitionResponse("cron_update", "Update cron jobs"),
            new AgentToolDefinitionResponse("cron_run", "Run cron jobs"),
            new AgentToolDefinitionResponse("cron_runs", "Inspect cron run history"),
            new AgentToolDefinitionResponse("memory_store", "Store memory"),
            new AgentToolDefinitionResponse("memory_recall", "Recall memory"),
            new AgentToolDefinitionResponse("memory_forget", "Forget memory"),
            new AgentToolDefinitionResponse("schedule", "Manage schedules"),
            new AgentToolDefinitionResponse("model_routing_config", "Update model routing"),
            new AgentToolDefinitionResponse("proxy_config", "Update proxy config"),
            new AgentToolDefinitionResponse("git_operations", "Run git operations"),
            new AgentToolDefinitionResponse("pushover", "Send notifications"),
            new AgentToolDefinitionResponse("pdf_read", "Read PDFs"),
            new AgentToolDefinitionResponse("screenshot", "Capture screenshots"),
            new AgentToolDefinitionResponse("image_info", "Inspect images"),
            new AgentToolDefinitionResponse("browser_open", "Open browser URLs"),
            new AgentToolDefinitionResponse("browser", "Use browser automation"),
            new AgentToolDefinitionResponse("http_request", "Send HTTP requests"),
            new AgentToolDefinitionResponse("web_fetch", "Fetch web pages"),
            new AgentToolDefinitionResponse("web_search_tool", "Search the web"),
            new AgentToolDefinitionResponse("composio", "Call Composio tools")
    );

    private final List<AgentToolPresetResponse> presets = List.of(
            new AgentToolPresetResponse(
                    "readonly",
                    "只读参考",
                    "适合读取 skill/reference 文件并在工作区内检索内容。",
                    List.of("file_read", "glob_search", "content_search")
            ),
            new AgentToolPresetResponse(
                    "writer_basic",
                    "写作基础",
                    "适合读取参考资料并输出生成结果。",
                    List.of("file_read", "glob_search", "content_search", "file_write")
            ),
            new AgentToolPresetResponse(
                    "writer_plus",
                    "写作增强",
                    "在写作基础上增加工作区内编辑能力。",
                    List.of("file_read", "glob_search", "content_search", "file_write", "file_edit")
            )
    );

    private final Map<String, AgentToolPresetResponse> presetByKey = presets.stream()
            .collect(Collectors.toUnmodifiableMap(AgentToolPresetResponse::key, Function.identity()));

    public AgentToolCatalogResponse getCatalog() {
        return new AgentToolCatalogResponse(tools, presets);
    }

    public String normalizePresetKey(String presetKey) {
        if (!StringUtils.hasText(presetKey)) {
            return null;
        }
        String normalized = presetKey.trim();
        if (!presetByKey.containsKey(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown tool preset: " + normalized);
        }
        return normalized;
    }

    public List<String> normalizeTools(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (!StringUtils.hasText(value)) {
                continue;
            }
            normalized.add(value.trim());
        }
        return List.copyOf(normalized);
    }

    public List<String> resolveTools(String presetKey, List<String> extraTools, List<String> deniedTools) {
        LinkedHashSet<String> resolved = new LinkedHashSet<>();
        String normalizedPresetKey = normalizePresetKey(presetKey);
        if (normalizedPresetKey != null) {
            resolved.addAll(presetByKey.get(normalizedPresetKey).tools());
        }
        resolved.addAll(normalizeTools(extraTools));
        resolved.removeAll(normalizeTools(deniedTools));
        return List.copyOf(resolved);
    }
}
