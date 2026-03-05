package com.fun.ai.claw.api.service;

import com.fun.ai.claw.api.model.AgentDescriptorResponse;
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
            "(?ms)^\\[agents\\.\"((?:\\\\.|[^\"\\\\])*)\"\\]\\s*(.*?)(?=^\\[[^\\]]+\\]|\\z)"
    );
    private static final Pattern PROVIDER_PATTERN = Pattern.compile("(?m)^\\s*provider\\s*=\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*$");
    private static final Pattern MODEL_PATTERN = Pattern.compile("(?m)^\\s*model\\s*=\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*$");
    private static final Pattern AGENTIC_PATTERN = Pattern.compile("(?m)^\\s*agentic\\s*=\\s*(true|false)\\s*$", Pattern.CASE_INSENSITIVE);

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
        if (!StringUtils.hasText(result.output())) {
            return List.of();
        }

        return parseAgents(result.output()).stream()
                .sorted(Comparator.comparing(AgentDescriptorResponse::id))
                .toList();
    }

    private List<AgentDescriptorResponse> parseAgents(String configText) {
        List<AgentDescriptorResponse> agents = new ArrayList<>();
        Matcher blockMatcher = AGENT_BLOCK_PATTERN.matcher(configText);
        while (blockMatcher.find()) {
            String id = unescapeTomlString(blockMatcher.group(1)).trim();
            if (!StringUtils.hasText(id)) {
                continue;
            }
            String block = blockMatcher.group(2);
            String provider = findStringValue(PROVIDER_PATTERN, block);
            String model = findStringValue(MODEL_PATTERN, block);
            Boolean agentic = findBooleanValue(AGENTIC_PATTERN, block);
            agents.add(new AgentDescriptorResponse(id, provider, model, agentic));
        }
        return agents;
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

    private String unescapeTomlString(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
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

    private record CommandResult(int exitCode, String output) {
    }
}
