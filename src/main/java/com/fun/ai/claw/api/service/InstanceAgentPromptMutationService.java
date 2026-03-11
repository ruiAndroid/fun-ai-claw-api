package com.fun.ai.claw.api.service;

import com.fun.ai.claw.api.model.AgentSystemPromptResponse;
import com.fun.ai.claw.api.model.InstanceConfigResponse;
import com.fun.ai.claw.api.model.UpsertAgentSystemPromptRequest;
import com.fun.ai.claw.api.model.UpsertInstanceConfigRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class InstanceAgentPromptMutationService {
    private static final Pattern SYSTEM_PROMPT_KEY_PATTERN = Pattern.compile("(?m)^\\s*system_prompt\\s*=");
    private static final Pattern TABLE_HEADER_PATTERN = Pattern.compile("(?m)^\\s*\\[\\[?.+?]]?\\s*$");

    private final InstanceConfigService instanceConfigService;
    private final InstanceConfigMutationService instanceConfigMutationService;

    public InstanceAgentPromptMutationService(InstanceConfigService instanceConfigService,
                                              InstanceConfigMutationService instanceConfigMutationService) {
        this.instanceConfigService = instanceConfigService;
        this.instanceConfigMutationService = instanceConfigMutationService;
    }

    public AgentSystemPromptResponse upsertSystemPrompt(UUID instanceId,
                                                        String agentId,
                                                        UpsertAgentSystemPromptRequest request) {
        String normalizedAgentId = normalizeAgentId(agentId);
        if (request == null || request.systemPrompt() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "systemPrompt is required");
        }

        String normalizedPrompt = normalizePrompt(request.systemPrompt());
        InstanceConfigResponse currentConfig = instanceConfigService.get(instanceId);
        String updatedToml = updateAgentSystemPrompt(currentConfig.configToml(), normalizedAgentId, normalizedPrompt);
        InstanceConfigResponse persisted = instanceConfigMutationService.upsert(
                instanceId,
                new UpsertInstanceConfigRequest(updatedToml, request.updatedBy())
        );
        return new AgentSystemPromptResponse(
                instanceId,
                normalizedAgentId,
                normalizedPrompt,
                persisted.runtimeConfigPath()
        );
    }

    private String updateAgentSystemPrompt(String configToml, String agentId, String prompt) {
        String normalizedToml = configToml == null ? "" : configToml.replace("\r\n", "\n");
        SectionRange sectionRange = findAgentSection(normalizedToml, agentId);
        if (sectionRange == null) {
            if (!StringUtils.hasText(prompt)) {
                return ensureTrailingNewline(normalizedToml);
            }
            return appendAgentSection(normalizedToml, agentId, prompt);
        }

        String sectionBody = normalizedToml.substring(sectionRange.bodyStart(), sectionRange.end());
        String updatedSectionBody = replaceSystemPromptInSection(sectionBody, prompt);
        return ensureTrailingNewline(
                normalizedToml.substring(0, sectionRange.bodyStart())
                        + updatedSectionBody
                        + normalizedToml.substring(sectionRange.end())
        );
    }

    private SectionRange findAgentSection(String configToml, String agentId) {
        Matcher matcher = TABLE_HEADER_PATTERN.matcher(configToml);
        List<HeaderRange> headers = new ArrayList<>();
        while (matcher.find()) {
            String headerLine = matcher.group().trim();
            headers.add(new HeaderRange(normalizeHeader(headerLine), matcher.start(), matcher.end()));
        }
        for (int index = 0; index < headers.size(); index++) {
            HeaderRange header = headers.get(index);
            if (!isTargetAgentHeader(header.header(), agentId)) {
                continue;
            }
            int bodyStart = skipNewline(configToml, header.end());
            int end = index + 1 < headers.size() ? headers.get(index + 1).start() : configToml.length();
            return new SectionRange(bodyStart, end);
        }
        return null;
    }

    private String replaceSystemPromptInSection(String sectionBody, String prompt) {
        Matcher matcher = SYSTEM_PROMPT_KEY_PATTERN.matcher(sectionBody);
        String renderedProperty = StringUtils.hasText(prompt)
                ? "system_prompt = " + renderTomlMultilineString(prompt) + "\n"
                : "";
        if (matcher.find()) {
            int propertyStart = matcher.start();
            int propertyEnd = findPropertyEnd(sectionBody, matcher.end());
            String updated = sectionBody.substring(0, propertyStart)
                    + renderedProperty
                    + sectionBody.substring(propertyEnd);
            return compactBlankLines(updated);
        }

        if (!StringUtils.hasText(prompt)) {
            return sectionBody;
        }
        return ensureSectionEndsWithSingleNewline(sectionBody) + renderedProperty;
    }

    private int findPropertyEnd(String sectionBody, int valueStartIndex) {
        int index = valueStartIndex;
        while (index < sectionBody.length() && (sectionBody.charAt(index) == ' ' || sectionBody.charAt(index) == '\t')) {
            index++;
        }
        if (startsWith(sectionBody, index, "\"\"\"") || startsWith(sectionBody, index, "'''")) {
            String delimiter = sectionBody.substring(index, index + 3);
            int closing = sectionBody.indexOf(delimiter, index + 3);
            if (closing < 0) {
                return sectionBody.length();
            }
            int end = closing + 3;
            while (end < sectionBody.length() && sectionBody.charAt(end) != '\n') {
                end++;
            }
            if (end < sectionBody.length()) {
                end++;
            }
            return end;
        }

        int end = index;
        while (end < sectionBody.length() && sectionBody.charAt(end) != '\n') {
            end++;
        }
        if (end < sectionBody.length()) {
            end++;
        }
        return end;
    }

    private String appendAgentSection(String configToml, String agentId, String prompt) {
        StringBuilder builder = new StringBuilder(configToml == null ? "" : configToml);
        if (builder.length() > 0 && builder.charAt(builder.length() - 1) != '\n') {
            builder.append('\n');
        }
        if (builder.length() > 0) {
            builder.append('\n');
        }
        builder.append("[agents.\"").append(escapeQuotedKey(agentId)).append("\"]\n");
        builder.append("system_prompt = ").append(renderTomlMultilineString(prompt)).append('\n');
        return ensureTrailingNewline(builder.toString());
    }

    private String renderTomlMultilineString(String prompt) {
        String normalized = normalizePrompt(prompt);
        if (!StringUtils.hasText(normalized)) {
            return "'''\n'''";
        }
        if (!normalized.contains("'''")) {
            return "'''\n" + normalized + "\n'''";
        }
        String escaped = normalized
                .replace("\\", "\\\\")
                .replace("\"\"\"", "\\\"\"\"");
        return "\"\"\"\n" + escaped + "\n\"\"\"";
    }

    private String normalizePrompt(String prompt) {
        if (prompt == null) {
            return null;
        }
        String normalized = prompt.replace("\r\n", "\n").trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String normalizeAgentId(String agentId) {
        if (!StringUtils.hasText(agentId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "agentId is required");
        }
        return agentId.trim();
    }

    private boolean isTargetAgentHeader(String header, String agentId) {
        return ("agents.\"" + agentId + "\"").equals(header)
                || ("agents.'" + agentId + "'").equals(header)
                || ("agents." + agentId).equals(header);
    }

    private String normalizeHeader(String rawLine) {
        String trimmed = rawLine.trim();
        if (trimmed.startsWith("[[") && trimmed.endsWith("]]")) {
            return trimmed.substring(2, trimmed.length() - 2).trim();
        }
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            return trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    private int skipNewline(String text, int index) {
        int cursor = index;
        while (cursor < text.length() && (text.charAt(cursor) == '\n' || text.charAt(cursor) == '\r')) {
            cursor++;
        }
        return cursor;
    }

    private boolean startsWith(String text, int offset, String prefix) {
        return offset >= 0
                && offset + prefix.length() <= text.length()
                && text.startsWith(prefix, offset);
    }

    private String compactBlankLines(String value) {
        String normalized = value.replace("\r\n", "\n");
        while (normalized.contains("\n\n\n")) {
            normalized = normalized.replace("\n\n\n", "\n\n");
        }
        return normalized;
    }

    private String ensureSectionEndsWithSingleNewline(String sectionBody) {
        String normalized = sectionBody.replace("\r\n", "\n");
        while (normalized.endsWith("\n\n")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (!normalized.endsWith("\n") && !normalized.isEmpty()) {
            normalized += "\n";
        }
        return normalized;
    }

    private String ensureTrailingNewline(String value) {
        String normalized = value.replace("\r\n", "\n");
        if (normalized.isEmpty() || normalized.endsWith("\n")) {
            return normalized;
        }
        return normalized + "\n";
    }

    private String escapeQuotedKey(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record HeaderRange(String header, int start, int end) {
    }

    private record SectionRange(int bodyStart, int end) {
    }
}
