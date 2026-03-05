package com.fun.ai.claw.api.service;

import com.fun.ai.claw.api.model.AgentDescriptorResponse;
import com.fun.ai.claw.api.model.AgentSystemPromptResponse;
import com.fun.ai.claw.api.repository.InstanceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class InstanceAgentService {

    private static final Pattern AGENT_BLOCK_PATTERN = Pattern.compile(
            "(?ms)^\\s*\\[\\s*agents\\s*\\.\\s*(?:\"((?:\\\\.|[^\"\\\\])*)\"|'((?:\\\\.|[^'\\\\])*)'|([A-Za-z0-9_-]+))\\s*\\]\\s*(.*?)(?=^\\s*\\[[^\\]]+\\]|\\z)"
    );
    private static final Pattern CONFIG_DIR_ARG_PATTERN = Pattern.compile("(?:^|\\s)--config-dir(?:=|\\s+)(\\S+)");
    private static final Pattern ZEROCLAW_CONFIG_DIR_ENV_PATTERN = Pattern.compile("(?m)^ZEROCLAW_CONFIG_DIR=(.+)$");
    private static final Pattern PROVIDER_PATTERN = Pattern.compile("(?m)^\\s*provider\\s*=\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*$");
    private static final Pattern MODEL_PATTERN = Pattern.compile("(?m)^\\s*model\\s*=\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*$");
    private static final Pattern SYSTEM_PROMPT_PATTERN = Pattern.compile("(?m)^\\s*system_prompt\\s*=\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*$");
    private static final Pattern SYSTEM_PROMPT_LITERAL_PATTERN = Pattern.compile("(?m)^\\s*system_prompt\\s*=\\s*'([^'\\r\\n]*)'\\s*$");
    private static final Pattern SYSTEM_PROMPT_MULTILINE_BASIC_PATTERN = Pattern.compile("(?ms)^\\s*system_prompt\\s*=\\s*\"\"\"(.*?)\"\"\"\\s*$");
    private static final Pattern SYSTEM_PROMPT_MULTILINE_LITERAL_PATTERN = Pattern.compile("(?ms)^\\s*system_prompt\\s*=\\s*'''(.*?)'''\\s*$");
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
        List<LoadedConfig> loadedConfigs = readConfigs(instanceId);
        if (loadedConfigs.isEmpty()) {
            return List.of();
        }

        LoadedConfig selectedConfig = loadedConfigs.get(0);
        List<AgentDescriptorResponse> selectedAgents = parseAgents(selectedConfig.text(), selectedConfig.path());
        for (LoadedConfig candidate : loadedConfigs) {
            List<AgentDescriptorResponse> candidateAgents = parseAgents(candidate.text(), candidate.path());
            if (!candidateAgents.isEmpty()) {
                selectedConfig = candidate;
                selectedAgents = candidateAgents;
                break;
            }
        }

        return selectedAgents.stream()
                .sorted(Comparator.comparing(AgentDescriptorResponse::id))
                .toList();
    }

    public AgentSystemPromptResponse getAgentSystemPrompt(UUID instanceId, String agentId) {
        String normalizedAgentId = normalizeAgentId(agentId);
        for (LoadedConfig loadedConfig : readConfigs(instanceId)) {
            AgentBlock agentBlock = findAgentBlockOrNull(loadedConfig.text(), normalizedAgentId);
            if (agentBlock == null) {
                continue;
            }
            return new AgentSystemPromptResponse(
                    instanceId,
                    normalizedAgentId,
                    findSystemPromptValue(agentBlock.block()),
                    loadedConfig.path()
            );
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "agent not found");
    }

    private List<LoadedConfig> readConfigs(UUID instanceId) {
        instanceRepository.findById(instanceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "instance not found"));
        String containerName = containerPrefix + "-" + instanceId;
        List<String> candidatePaths = resolveConfigPathCandidates(containerName);
        List<LoadedConfig> loadedConfigs = new ArrayList<>();
        CommandResult lastFailure = null;
        for (String candidatePath : candidatePaths) {
            CommandResult result = runCommand(
                    dockerCommand,
                    "exec",
                    containerName,
                    "/bin/busybox",
                    "cat",
                    candidatePath
            );
            if (result.exitCode == 0) {
                loadedConfigs.add(new LoadedConfig(candidatePath, result.output()));
                continue;
            }
            lastFailure = result;
        }
        if (!loadedConfigs.isEmpty()) {
            return loadedConfigs;
        }
        String details = lastFailure != null && StringUtils.hasText(lastFailure.output())
                ? ": " + lastFailure.output().trim()
                : "";
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "failed to read instance config" + details);
    }

    private List<String> resolveConfigPathCandidates(String containerName) {
        List<String> candidates = new ArrayList<>();
        String inspectedArgsPath = inspectContainerConfigPathFromArgs(containerName);
        if (StringUtils.hasText(inspectedArgsPath)) {
            candidates.add(inspectedArgsPath);
        }
        String inspectedEnvPath = inspectContainerConfigPathFromEnv(containerName);
        if (StringUtils.hasText(inspectedEnvPath) && !candidates.contains(inspectedEnvPath)) {
            candidates.add(inspectedEnvPath);
        }
        if (!candidates.contains(configPath)) {
            candidates.add(configPath);
        }
        return candidates;
    }

    private String inspectContainerConfigPathFromArgs(String containerName) {
        CommandResult inspect = runCommand(
                dockerCommand,
                "inspect",
                "-f",
                "{{range .Args}}{{.}} {{end}}",
                containerName
        );
        if (inspect.exitCode != 0 || !StringUtils.hasText(inspect.output())) {
            return null;
        }
        Matcher matcher = CONFIG_DIR_ARG_PATTERN.matcher(inspect.output());
        if (!matcher.find()) {
            return null;
        }
        String configDir = matcher.group(1) == null ? "" : matcher.group(1).trim();
        if (!StringUtils.hasText(configDir)) {
            return null;
        }
        return configDir.endsWith("/") ? configDir + "config.toml" : configDir + "/config.toml";
    }

    private String inspectContainerConfigPathFromEnv(String containerName) {
        CommandResult inspect = runCommand(
                dockerCommand,
                "inspect",
                "-f",
                "{{range .Config.Env}}{{println .}}{{end}}",
                containerName
        );
        if (inspect.exitCode != 0 || !StringUtils.hasText(inspect.output())) {
            return null;
        }
        Matcher matcher = ZEROCLAW_CONFIG_DIR_ENV_PATTERN.matcher(inspect.output());
        if (!matcher.find()) {
            return null;
        }
        String configDir = matcher.group(1) == null ? "" : matcher.group(1).trim();
        if (!StringUtils.hasText(configDir)) {
            return null;
        }
        return configDir.endsWith("/") ? configDir + "config.toml" : configDir + "/config.toml";
    }

    private List<AgentDescriptorResponse> parseAgents(String configText, String resolvedConfigPath) {
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
            String systemPrompt = findSystemPromptValue(block);
            Boolean agentic = findBooleanValue(AGENTIC_PATTERN, block);
            List<String> allowedTools = findStringArrayValue(ALLOWED_TOOLS_PATTERN, block);
            agents.add(new AgentDescriptorResponse(id, provider, model, agentic, allowedTools, systemPrompt, resolvedConfigPath));
        }
        return agents;
    }

    private AgentBlock findAgentBlockOrNull(String configText, String targetAgentId) {
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
                return new AgentBlock(id, blockMatcher.start(), blockMatcher.end(), blockMatcher.group(4));
            }
        }
        return null;
    }

    private String normalizeAgentId(String agentId) {
        if (!StringUtils.hasText(agentId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "agentId is required");
        }
        return agentId.trim();
    }

    private String findSystemPromptValue(String block) {
        String basicValue = findStringValue(SYSTEM_PROMPT_PATTERN, block);
        if (basicValue != null) {
            return basicValue;
        }

        Matcher literalMatcher = SYSTEM_PROMPT_LITERAL_PATTERN.matcher(block);
        if (literalMatcher.find()) {
            return literalMatcher.group(1);
        }

        Matcher multilineBasicMatcher = SYSTEM_PROMPT_MULTILINE_BASIC_PATTERN.matcher(block);
        if (multilineBasicMatcher.find()) {
            String raw = normalizeTomlMultilineBody(multilineBasicMatcher.group(1));
            return unescapeTomlString(raw);
        }

        Matcher multilineLiteralMatcher = SYSTEM_PROMPT_MULTILINE_LITERAL_PATTERN.matcher(block);
        if (multilineLiteralMatcher.find()) {
            return normalizeTomlMultilineBody(multilineLiteralMatcher.group(1));
        }

        return null;
    }

    private String normalizeTomlMultilineBody(String value) {
        if (value == null) {
            return "";
        }
        if (value.startsWith("\r\n")) {
            return value.substring(2);
        }
        if (value.startsWith("\n")) {
            return value.substring(1);
        }
        return value;
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

    private record AgentBlock(String id, int start, int end, String block) {
    }

    private record LoadedConfig(String path, String text) {
    }

    private record CommandResult(int exitCode, String output) {
    }
}
