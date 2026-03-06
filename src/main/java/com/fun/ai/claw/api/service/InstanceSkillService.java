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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    private static final Pattern CONFIG_DIR_ARG_PATTERN = Pattern.compile("(?:^|\\s)--config-dir(?:=|\\s+)(\\S+)");
    private static final Pattern ZEROCLAW_CONFIG_DIR_ENV_PATTERN = Pattern.compile("(?m)^ZEROCLAW_CONFIG_DIR=(.+)$");
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
    private final List<String> fallbackSkillDirs;

    public InstanceSkillService(InstanceRepository instanceRepository,
                                @Value("${app.instance-skills.docker-command:docker}") String dockerCommand,
                                @Value("${app.instance-skills.container-prefix:funclaw}") String containerPrefix,
                                @Value("${app.instance-skills.config-path:/data/zeroclaw/config.toml}") String configPath,
                                @Value("${app.instance-skills.fallback-skill-dirs:/zeroclaw-data/workspace/skills,/workspace/agent-mgc-novel-script/skills}") List<String> fallbackSkillDirs,
                                @Value("${app.instance-skills.command-timeout-seconds:8}") long commandTimeoutSeconds) {
        this.instanceRepository = instanceRepository;
        this.dockerCommand = StringUtils.hasText(dockerCommand) ? dockerCommand.trim() : "docker";
        this.containerPrefix = StringUtils.hasText(containerPrefix) ? containerPrefix.trim() : "funclaw";
        this.configPath = StringUtils.hasText(configPath) ? configPath.trim() : "/data/zeroclaw/config.toml";
        this.fallbackSkillDirs = normalizeFallbackSkillDirs(fallbackSkillDirs);
        long timeoutSeconds = commandTimeoutSeconds > 0 ? commandTimeoutSeconds : 8;
        this.commandTimeout = Duration.ofSeconds(timeoutSeconds);
    }

    public List<SkillDescriptorResponse> listSkills(UUID instanceId) {
        instanceRepository.findById(instanceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "instance not found"));

        String containerName = containerPrefix + "-" + instanceId;
        LoadedConfig loadedConfig = readConfig(containerName);
        if (!StringUtils.hasText(loadedConfig.text())) {
            return List.of();
        }

        SkillsConfig skillsConfig = parseSkillsConfig(loadedConfig.text());
        List<String> skillDirs = resolveSkillDirs(skillsConfig);
        if (skillDirs.isEmpty()) {
            return List.of();
        }

        LinkedHashMap<String, SkillDescriptorResponse> skillsById = new LinkedHashMap<>();
        for (String skillDir : skillDirs) {
            List<String> skillFiles = listSkillFiles(containerName, skillDir);
            for (String skillFilePath : skillFiles) {
                String skillId = resolveSkillId(skillFilePath);
                if (skillsById.containsKey(skillId)) {
                    continue;
                }
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
                skillsById.put(skillId, new SkillDescriptorResponse(
                        skillId,
                        skillFilePath,
                        prompt
                ));
            }
        }

        return skillsById.values().stream()
                .sorted(Comparator.comparing(SkillDescriptorResponse::id))
                .toList();
    }

    private List<String> resolveSkillDirs(SkillsConfig skillsConfig) {
        LinkedHashSet<String> orderedDirs = new LinkedHashSet<>();
        if (skillsConfig.openSkillsEnabled() && StringUtils.hasText(skillsConfig.openSkillsDir())) {
            orderedDirs.add(skillsConfig.openSkillsDir().trim());
        }
        orderedDirs.addAll(fallbackSkillDirs);
        return orderedDirs.stream()
                .filter(StringUtils::hasText)
                .toList();
    }

    private List<String> normalizeFallbackSkillDirs(List<String> rawDirs) {
        if (rawDirs == null || rawDirs.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String rawDir : rawDirs) {
            if (!StringUtils.hasText(rawDir)) {
                continue;
            }
            String trimmed = rawDir.trim();
            if (trimmed.contains(",")) {
                for (String split : trimmed.split(",")) {
                    if (StringUtils.hasText(split)) {
                        normalized.add(split.trim());
                    }
                }
                continue;
            }
            normalized.add(trimmed);
        }
        return List.copyOf(normalized);
    }

    private LoadedConfig readConfig(String containerName) {
        List<String> candidatePaths = resolveConfigPathCandidates(containerName);
        CommandResult lastFailure = null;
        for (String candidatePath : candidatePaths) {
            CommandResult configResult = runCommand(
                    dockerCommand,
                    "exec",
                    containerName,
                    "/bin/busybox",
                    "cat",
                    candidatePath
            );
            if (configResult.exitCode == 0) {
                return new LoadedConfig(candidatePath, configResult.output);
            }
            lastFailure = configResult;
        }
        String details = lastFailure != null && StringUtils.hasText(lastFailure.output)
                ? ": " + lastFailure.output.trim()
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
        if (inspect.exitCode != 0 || !StringUtils.hasText(inspect.output)) {
            return null;
        }
        Matcher matcher = CONFIG_DIR_ARG_PATTERN.matcher(inspect.output);
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
        if (inspect.exitCode != 0 || !StringUtils.hasText(inspect.output)) {
            return null;
        }
        Matcher matcher = ZEROCLAW_CONFIG_DIR_ENV_PATTERN.matcher(inspect.output);
        if (!matcher.find()) {
            return null;
        }
        String configDir = matcher.group(1) == null ? "" : matcher.group(1).trim();
        if (!StringUtils.hasText(configDir)) {
            return null;
        }
        return configDir.endsWith("/") ? configDir + "config.toml" : configDir + "/config.toml";
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

    private record LoadedConfig(String path, String text) {
    }

    private record CommandResult(int exitCode, String output) {
    }
}
