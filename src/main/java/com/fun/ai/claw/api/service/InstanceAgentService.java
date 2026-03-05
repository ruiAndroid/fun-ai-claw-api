package com.fun.ai.claw.api.service;

import com.fun.ai.claw.api.model.AgentDescriptorResponse;
import com.fun.ai.claw.api.model.AgentSystemPromptResponse;
import com.fun.ai.claw.api.model.UpsertAgentSystemPromptRequest;
import com.fun.ai.claw.api.repository.InstanceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class InstanceAgentService {

    private static final Pattern AGENT_BLOCK_PATTERN = Pattern.compile(
            "(?ms)^\\s*\\[\\s*agents\\s*\\.\\s*(?:\"((?:\\\\.|[^\"\\\\])*)\"|'((?:\\\\.|[^'\\\\])*)'|([A-Za-z0-9_-]+))\\s*\\]\\s*(.*?)(?=^\\s*\\[[^\\]]+\\]|\\z)"
    );
    private static final Pattern PROVIDER_PATTERN = Pattern.compile("(?m)^\\s*provider\\s*=\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*$");
    private static final Pattern MODEL_PATTERN = Pattern.compile("(?m)^\\s*model\\s*=\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*$");
    private static final Pattern SYSTEM_PROMPT_PATTERN = Pattern.compile("(?m)^\\s*system_prompt\\s*=\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*$");
    private static final Pattern SYSTEM_PROMPT_LINE_PATTERN = Pattern.compile("(?m)^(\\s*)system_prompt\\s*=\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*$");
    private static final Pattern AGENTIC_PATTERN = Pattern.compile("(?m)^\\s*agentic\\s*=\\s*(true|false)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALLOWED_TOOLS_PATTERN = Pattern.compile("(?ms)^\\s*allowed_tools\\s*=\\s*\\[(.*?)]\\s*$");
    private static final Pattern ARRAY_QUOTED_STRING_PATTERN = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"|'((?:\\\\.|[^'\\\\])*)'");

    private final InstanceRepository instanceRepository;
    private final String dockerCommand;
    private final String containerPrefix;
    private final String configPath;
    private final Duration commandTimeout;

    public InstanceAgentService(InstanceRepository instanceRepository,
                                @Value("${app.instance-agents.docker-command:docker}") String dockerCommand,
                                @Value("${app.instance-agents.container-prefix:funclaw}") String containerPrefix,
                                @Value("${app.instance-agents.config-path:/data/zeroclaw/config.toml}") String configPath,
                                @Value("${app.instance-agents.command-timeout-seconds:8}") long commandTimeoutSeconds) {
        this.instanceRepository = instanceRepository;
        this.dockerCommand = StringUtils.hasText(dockerCommand) ? dockerCommand.trim() : "docker";
        this.containerPrefix = StringUtils.hasText(containerPrefix) ? containerPrefix.trim() : "funclaw";
        this.configPath = StringUtils.hasText(configPath) ? configPath.trim() : "/data/zeroclaw/config.toml";
        long timeoutSeconds = commandTimeoutSeconds > 0 ? commandTimeoutSeconds : 8;
        this.commandTimeout = Duration.ofSeconds(timeoutSeconds);
    }

    public List<AgentDescriptorResponse> listAgents(UUID instanceId) {
        String configText = readConfigText(instanceId);
        if (!StringUtils.hasText(configText)) {
            return List.of();
        }

        return parseAgents(configText).stream()
                .sorted(Comparator.comparing(AgentDescriptorResponse::id))
                .toList();
    }

    public AgentSystemPromptResponse getAgentSystemPrompt(UUID instanceId, String agentId) {
        String normalizedAgentId = normalizeAgentId(agentId);
        String configText = readConfigText(instanceId);
        AgentBlock agentBlock = findAgentBlock(configText, normalizedAgentId);
        return new AgentSystemPromptResponse(
                instanceId,
                normalizedAgentId,
                findStringValue(SYSTEM_PROMPT_PATTERN, agentBlock.block()),
                configPath
        );
    }

    public AgentSystemPromptResponse upsertAgentSystemPrompt(UUID instanceId,
                                                             String agentId,
                                                             UpsertAgentSystemPromptRequest request) {
        String normalizedAgentId = normalizeAgentId(agentId);
        String configText = readConfigText(instanceId);
        AgentBlock agentBlock = findAgentBlock(configText, normalizedAgentId);
        String normalizedPrompt = request == null ? "" : Objects.toString(request.systemPrompt(), "");
        String escapedPrompt = escapeTomlBasicString(normalizedPrompt);
        String updatedBlock = upsertSystemPromptInBlock(agentBlock.block(), escapedPrompt);

        String updatedConfig = configText.substring(0, agentBlock.start())
                + updatedBlock
                + configText.substring(agentBlock.end());
        writeConfigText(instanceId, updatedConfig);

        return new AgentSystemPromptResponse(
                instanceId,
                normalizedAgentId,
                normalizedPrompt,
                configPath
        );
    }

    private String readConfigText(UUID instanceId) {
        instanceRepository.findById(instanceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "instance not found"));
        String containerName = containerPrefix + "-" + instanceId;
        CommandResult result = runCommand(
                dockerCommand,
                "exec",
                containerName,
                "/bin/busybox",
                "cat",
                configPath
        );
        if (result.exitCode != 0) {
            String details = StringUtils.hasText(result.output()) ? ": " + result.output().trim() : "";
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "failed to read instance config" + details);
        }
        return result.output();
    }

    private void writeConfigText(UUID instanceId, String configText) {
        instanceRepository.findById(instanceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "instance not found"));
        String containerName = containerPrefix + "-" + instanceId;
        String script = "cat > " + shellSingleQuote(configPath);
        CommandResult result = runCommandWithInput(
                configText,
                dockerCommand,
                "exec",
                "-i",
                containerName,
                "/bin/busybox",
                "sh",
                "-lc",
                script
        );
        if (result.exitCode != 0) {
            String details = StringUtils.hasText(result.output()) ? ": " + result.output().trim() : "";
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "failed to write instance config" + details);
        }
    }

    private List<AgentDescriptorResponse> parseAgents(String configText) {
        List<AgentDescriptorResponse> agents = new ArrayList<>();
        Matcher blockMatcher = AGENT_BLOCK_PATTERN.matcher(configText);
        while (blockMatcher.find()) {
            String rawId = firstNonBlank(
                    blockMatcher.group(1),
                    blockMatcher.group(2),
                    blockMatcher.group(3)
            );
            String id = unescapeTomlString(rawId).trim();
            if (!StringUtils.hasText(id)) {
                continue;
            }
            String block = blockMatcher.group(4);
            String provider = findStringValue(PROVIDER_PATTERN, block);
            String model = findStringValue(MODEL_PATTERN, block);
            String systemPrompt = findStringValue(SYSTEM_PROMPT_PATTERN, block);
            Boolean agentic = findBooleanValue(AGENTIC_PATTERN, block);
            List<String> allowedTools = findStringArrayValue(ALLOWED_TOOLS_PATTERN, block);
            agents.add(new AgentDescriptorResponse(id, provider, model, agentic, allowedTools, systemPrompt, configPath));
        }
        return agents;
    }

    private AgentBlock findAgentBlock(String configText, String targetAgentId) {
        Matcher blockMatcher = AGENT_BLOCK_PATTERN.matcher(configText);
        while (blockMatcher.find()) {
            String rawId = firstNonBlank(
                    blockMatcher.group(1),
                    blockMatcher.group(2),
                    blockMatcher.group(3)
            );
            String id = unescapeTomlString(rawId).trim();
            if (!StringUtils.hasText(id)) {
                continue;
            }
            if (id.equals(targetAgentId)) {
                return new AgentBlock(id, blockMatcher.start(), blockMatcher.end(), blockMatcher.group(0));
            }
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "agent not found");
    }

    private String normalizeAgentId(String agentId) {
        if (!StringUtils.hasText(agentId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "agentId is required");
        }
        return agentId.trim();
    }

    private String upsertSystemPromptInBlock(String block, String escapedPrompt) {
        Matcher matcher = SYSTEM_PROMPT_LINE_PATTERN.matcher(block);
        String replacementLine = "system_prompt = \"" + escapedPrompt + "\"";
        if (matcher.find()) {
            String indent = matcher.group(1) == null ? "" : matcher.group(1);
            return block.substring(0, matcher.start())
                    + indent
                    + replacementLine
                    + block.substring(matcher.end());
        }
        if (block.endsWith("\n")) {
            return block + replacementLine + "\n";
        }
        return block + "\n" + replacementLine + "\n";
    }

    private String firstNonBlank(String... values) {
        if (values == null || values.length == 0) {
            return "";
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return "";
    }

    private String findStringValue(Pattern pattern, String block) {
        Matcher matcher = pattern.matcher(block);
        if (!matcher.find()) {
            return null;
        }
        return unescapeTomlString(matcher.group(1)).trim();
    }

    private Boolean findBooleanValue(Pattern pattern, String block) {
        Matcher matcher = pattern.matcher(block);
        if (!matcher.find()) {
            return null;
        }
        return Boolean.parseBoolean(matcher.group(1).trim());
    }

    private List<String> findStringArrayValue(Pattern pattern, String block) {
        Matcher matcher = pattern.matcher(block);
        if (!matcher.find()) {
            return List.of();
        }
        String arrayBody = matcher.group(1);
        Matcher valueMatcher = ARRAY_QUOTED_STRING_PATTERN.matcher(arrayBody);
        List<String> values = new ArrayList<>();
        while (valueMatcher.find()) {
            String raw = StringUtils.hasText(valueMatcher.group(1))
                    ? valueMatcher.group(1)
                    : valueMatcher.group(2);
            String value = unescapeTomlString(raw).trim();
            if (StringUtils.hasText(value)) {
                values.add(value);
            }
        }
        return values;
    }

    private String unescapeTomlString(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch != '\\' || index + 1 >= value.length()) {
                builder.append(ch);
                continue;
            }
            char next = value.charAt(++index);
            switch (next) {
                case 'n' -> builder.append('\n');
                case 'r' -> builder.append('\r');
                case 't' -> builder.append('\t');
                case '"' -> builder.append('"');
                case '\\' -> builder.append('\\');
                default -> builder.append(next);
            }
        }
        return builder.toString();
    }

    private String escapeTomlBasicString(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }

    private String shellSingleQuote(String value) {
        if (value == null) {
            return "''";
        }
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private CommandResult runCommand(String... command) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            process.getInputStream().transferTo(output);
            boolean finished = process.waitFor(commandTimeout.toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new CommandResult(124, "command timed out");
            }
            return new CommandResult(process.exitValue(), output.toString(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            return new CommandResult(1, "io error: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new CommandResult(130, "interrupted");
        }
    }

    private CommandResult runCommandWithInput(String input, String... command) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            try (OutputStream stdin = process.getOutputStream()) {
                if (input != null) {
                    stdin.write(input.getBytes(StandardCharsets.UTF_8));
                }
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            process.getInputStream().transferTo(output);
            boolean finished = process.waitFor(commandTimeout.toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new CommandResult(124, "command timed out");
            }
            return new CommandResult(process.exitValue(), output.toString(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            return new CommandResult(1, "io error: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new CommandResult(130, "interrupted");
        }
    }

    private record AgentBlock(String id, int start, int end, String block) {
    }

    private record CommandResult(int exitCode, String output) {
    }
}
