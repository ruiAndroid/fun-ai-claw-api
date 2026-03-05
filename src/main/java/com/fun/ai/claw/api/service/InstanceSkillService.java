package com.fun.ai.claw.api.service;

import com.fun.ai.claw.api.model.SkillDescriptorResponse;
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
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class InstanceSkillService {

    private static final Pattern SKILLS_BLOCK_PATTERN = Pattern.compile(
            "(?ms)^\\s*\\[\\s*skills\\s*]\\s*(.*?)(?=^\\s*\\[[^\\]]+\\]|\\z)"
    );
    private static final Pattern OPEN_SKILLS_ENABLED_PATTERN = Pattern.compile(
            "(?m)^\\s*open_skills_enabled\\s*=\\s*(true|false)\\s*$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern OPEN_SKILLS_DIR_PATTERN = Pattern.compile(
            "(?m)^\\s*open_skills_dir\\s*=\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*$"
    );

    private final InstanceRepository instanceRepository;
    private final String dockerCommand;
    private final String containerPrefix;
    private final String configPath;
    private final Duration commandTimeout;

    public InstanceSkillService(InstanceRepository instanceRepository,
                                @Value("${app.instance-skills.docker-command:docker}") String dockerCommand,
                                @Value("${app.instance-skills.container-prefix:funclaw}") String containerPrefix,
                                @Value("${app.instance-skills.config-path:/data/zeroclaw/config.toml}") String configPath,
                                @Value("${app.instance-skills.command-timeout-seconds:8}") long commandTimeoutSeconds) {
        this.instanceRepository = instanceRepository;
        this.dockerCommand = StringUtils.hasText(dockerCommand) ? dockerCommand.trim() : "docker";
        this.containerPrefix = StringUtils.hasText(containerPrefix) ? containerPrefix.trim() : "funclaw";
        this.configPath = StringUtils.hasText(configPath) ? configPath.trim() : "/data/zeroclaw/config.toml";
        long timeoutSeconds = commandTimeoutSeconds > 0 ? commandTimeoutSeconds : 8;
        this.commandTimeout = Duration.ofSeconds(timeoutSeconds);
    }

    public List<SkillDescriptorResponse> listSkills(UUID instanceId) {
        instanceRepository.findById(instanceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "instance not found"));

        String containerName = containerPrefix + "-" + instanceId;
        CommandResult configResult = runCommand(
                dockerCommand,
                "exec",
                containerName,
                "/bin/busybox",
                "cat",
                configPath
        );
        if (configResult.exitCode != 0) {
            String details = StringUtils.hasText(configResult.output) ? ": " + configResult.output.trim() : "";
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "failed to read instance config" + details);
        }
        if (!StringUtils.hasText(configResult.output)) {
            return List.of();
        }

        SkillsConfig skillsConfig = parseSkillsConfig(configResult.output);
        if (!skillsConfig.openSkillsEnabled() || !StringUtils.hasText(skillsConfig.openSkillsDir())) {
            return List.of();
        }

        List<String> skillFiles = listSkillFiles(containerName, skillsConfig.openSkillsDir());
        List<SkillDescriptorResponse> skills = new ArrayList<>();
        for (String skillFilePath : skillFiles) {
            CommandResult skillResult = runCommand(
                    dockerCommand,
                    "exec",
                    containerName,
                    "/bin/busybox",
                    "cat",
                    skillFilePath
            );
            if (skillResult.exitCode != 0) {
                continue;
            }
            String prompt = normalizeText(skillResult.output);
            skills.add(new SkillDescriptorResponse(
                    resolveSkillId(skillFilePath),
                    skillFilePath,
                    prompt
            ));
        }
        return skills.stream()
                .sorted(Comparator.comparing(SkillDescriptorResponse::id))
                .toList();
    }

    private SkillsConfig parseSkillsConfig(String configText) {
        Matcher blockMatcher = SKILLS_BLOCK_PATTERN.matcher(configText);
        if (!blockMatcher.find()) {
            return new SkillsConfig(false, null);
        }
        String block = blockMatcher.group(1);
        boolean openSkillsEnabled = false;
        Matcher enabledMatcher = OPEN_SKILLS_ENABLED_PATTERN.matcher(block);
        if (enabledMatcher.find()) {
            openSkillsEnabled = Boolean.parseBoolean(enabledMatcher.group(1).trim());
        }

        String openSkillsDir = null;
        Matcher dirMatcher = OPEN_SKILLS_DIR_PATTERN.matcher(block);
        if (dirMatcher.find()) {
            openSkillsDir = unescapeTomlString(dirMatcher.group(1)).trim();
        }
        return new SkillsConfig(openSkillsEnabled, openSkillsDir);
    }

    private List<String> listSkillFiles(String containerName, String openSkillsDir) {
        CommandResult listResult = runCommand(
                dockerCommand,
                "exec",
                containerName,
                "/bin/busybox",
                "find",
                openSkillsDir,
                "-mindepth",
                "2",
                "-maxdepth",
                "2",
                "-type",
                "f",
                "-name",
                "SKILL.md"
        );

        if (listResult.exitCode != 0) {
            String outputLower = listResult.output == null ? "" : listResult.output.toLowerCase(Locale.ROOT);
            if (outputLower.contains("no such file")) {
                return List.of();
            }
            String details = StringUtils.hasText(listResult.output) ? ": " + listResult.output.trim() : "";
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "failed to list skills" + details);
        }

        return listResult.output == null
                ? List.of()
                : listResult.output.lines()
                .map(String::trim)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }

    private String resolveSkillId(String skillFilePath) {
        String normalized = skillFilePath == null ? "" : skillFilePath.replace("\\", "/");
        int lastSlash = normalized.lastIndexOf('/');
        if (lastSlash <= 0) {
            return normalized;
        }
        int parentSlash = normalized.lastIndexOf('/', lastSlash - 1);
        if (parentSlash >= 0 && parentSlash < lastSlash) {
            return normalized.substring(parentSlash + 1, lastSlash);
        }
        return normalized.substring(0, lastSlash);
    }

    private String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n").trim();
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

    private record SkillsConfig(boolean openSkillsEnabled, String openSkillsDir) {
    }

    private record CommandResult(int exitCode, String output) {
    }
}
