package com.fun.ai.claw.api.service;

import com.fun.ai.claw.api.model.ClawInstanceDto;
import com.fun.ai.claw.api.model.PairingCodeResponse;
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
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PairingCodeService {

    private static final Pattern SIX_DIGIT_PATTERN = Pattern.compile("(?<!\\d)(\\d{6})(?!\\d)");

    private final InstanceRepository instanceRepository;
    private final String dockerCommand;
    private final String containerPrefix;
    private final int logTailLines;
    private final Duration commandTimeout;

    public PairingCodeService(InstanceRepository instanceRepository,
                              @Value("${app.terminal.docker-command:docker}") String dockerCommand,
                              @Value("${app.terminal.container-prefix:funclaw}") String containerPrefix,
                              @Value("${app.pairing-code.log-tail-lines:300}") int logTailLines,
                              @Value("${app.pairing-code.command-timeout-seconds:8}") long commandTimeoutSeconds) {
        this.instanceRepository = instanceRepository;
        this.dockerCommand = dockerCommand;
        this.containerPrefix = containerPrefix;
        this.logTailLines = Math.max(100, logTailLines);
        this.commandTimeout = Duration.ofSeconds(Math.max(3, commandTimeoutSeconds));
    }

    public PairingCodeResponse fetchPairingCode(UUID instanceId) {
        ClawInstanceDto instance = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "instance not found"));
        String containerName = containerPrefix + "-" + instance.id();
        Instant fetchedAt = Instant.now();

        LogReadResult logReadResult = readContainerLogs(containerName);
        if (!logReadResult.succeeded()) {
            return new PairingCodeResponse(
                    instance.id(),
                    null,
                    null,
                    logReadResult.message(),
                    fetchedAt
            );
        }

        PairingCodeMatch match = extractPairingCode(logReadResult.output());
        if (match == null) {
            return new PairingCodeResponse(
                    instance.id(),
                    null,
                    null,
                    "pairing code not found in recent container logs",
                    fetchedAt
            );
        }

        return new PairingCodeResponse(
                instance.id(),
                match.code(),
                match.sourceLine(),
                null,
                fetchedAt
        );
    }

    private LogReadResult readContainerLogs(String containerName) {
        List<String> command = List.of(
                dockerCommand,
                "logs",
                "--tail",
                String.valueOf(logTailLines),
                containerName
        );
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        try {
            Process process = processBuilder.start();
            ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream();
            process.getInputStream().transferTo(outputBuffer);
            boolean finished = process.waitFor(commandTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new LogReadResult(false, "", "timed out while reading container logs");
            }

            String output = outputBuffer.toString(StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                String message = StringUtils.hasText(output)
                        ? output.trim()
                        : "docker logs exited with non-zero status";
                return new LogReadResult(false, output, message);
            }
            return new LogReadResult(true, output, null);
        } catch (IOException ex) {
            return new LogReadResult(false, "", "failed to execute docker logs: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new LogReadResult(false, "", "docker logs command interrupted");
        }
    }

    private PairingCodeMatch extractPairingCode(String logs) {
        if (!StringUtils.hasText(logs)) {
            return null;
        }
        String[] lines = logs.split("\\R");
        PairingCodeMatch matchWithHint = findMatchWithHint(lines);
        if (matchWithHint != null) {
            return matchWithHint;
        }
        return findAnySixDigitMatch(lines);
    }

    private PairingCodeMatch findMatchWithHint(String[] lines) {
        for (int index = lines.length - 1; index >= 0; index--) {
            String line = lines[index];
            if (!StringUtils.hasText(line)) {
                continue;
            }
            String normalized = line.toLowerCase(Locale.ROOT);
            if (!normalized.contains("pair") && !normalized.contains("code")) {
                continue;
            }
            Matcher matcher = SIX_DIGIT_PATTERN.matcher(line);
            if (matcher.find()) {
                return new PairingCodeMatch(matcher.group(1), line.trim());
            }
        }
        return null;
    }

    private PairingCodeMatch findAnySixDigitMatch(String[] lines) {
        for (int index = lines.length - 1; index >= 0; index--) {
            String line = lines[index];
            if (!StringUtils.hasText(line)) {
                continue;
            }
            Matcher matcher = SIX_DIGIT_PATTERN.matcher(line);
            if (matcher.find()) {
                return new PairingCodeMatch(matcher.group(1), line.trim());
            }
        }
        return null;
    }

    private record PairingCodeMatch(String code, String sourceLine) {
    }

    private record LogReadResult(boolean succeeded, String output, String message) {
    }
}

