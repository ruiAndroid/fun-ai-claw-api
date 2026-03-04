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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PairingCodeService {

    private static final Pattern SIX_DIGIT_PATTERN = Pattern.compile("(?<!\\d)(\\d{6})(?!\\d)");
    private static final Pattern NUMERIC_CODE_PATTERN = Pattern.compile("(?<!\\d)(\\d{6,8})(?!\\d)");
    private static final Pattern HINTED_CODE_PATTERN = Pattern.compile(
            "(?i)(?:pair(?:ing)?(?:\\s*(?:code|token|pin))?|verification\\s*code|otp|code)\\D{0,20}([A-Za-z0-9\\-]{4,24})"
    );
    private static final Pattern CODE_QUERY_PATTERN = Pattern.compile(
            "(?i)(?:[?&](?:pair(?:ing)?[_-]?code|verification[_-]?code|otp|code)=)([A-Za-z0-9\\-]{4,24})"
    );
    private static final Pattern ALPHANUMERIC_TOKEN_PATTERN = Pattern.compile("(?<![A-Za-z0-9])([A-Za-z0-9\\-]{4,24})(?![A-Za-z0-9])");
    private static final Pattern DATE_LIKE_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Set<String> HINT_KEYWORDS = Set.of(
            "pair",
            "pairing",
            "code",
            "otp",
            "verification"
    );
    private static final Set<String> CANDIDATE_STOP_WORDS = Set.of(
            "pair",
            "pairing",
            "code",
            "token",
            "pin",
            "otp",
            "none",
            "null",
            "error",
            "failed",
            "failure",
            "found",
            "status",
            "true",
            "false",
            "http",
            "https"
    );

    private final InstanceRepository instanceRepository;
    private final String dockerCommand;
    private final String containerPrefix;
    private final int logTailLines;
    private final Duration commandTimeout;
    private final int fetchAttempts;
    private final Duration fetchRetryInterval;

    public PairingCodeService(InstanceRepository instanceRepository,
                              @Value("${app.terminal.docker-command:docker}") String dockerCommand,
                              @Value("${app.terminal.container-prefix:funclaw}") String containerPrefix,
                              @Value("${app.pairing-code.log-tail-lines:300}") int logTailLines,
                              @Value("${app.pairing-code.command-timeout-seconds:8}") long commandTimeoutSeconds,
                              @Value("${app.pairing-code.fetch-attempts:4}") int fetchAttempts,
                              @Value("${app.pairing-code.fetch-retry-interval-millis:1000}") long fetchRetryIntervalMillis) {
        this.instanceRepository = instanceRepository;
        this.dockerCommand = dockerCommand;
        this.containerPrefix = containerPrefix;
        this.logTailLines = Math.max(100, logTailLines);
        this.commandTimeout = Duration.ofSeconds(Math.max(3, commandTimeoutSeconds));
        this.fetchAttempts = Math.max(1, fetchAttempts);
        this.fetchRetryInterval = Duration.ofMillis(Math.max(200, fetchRetryIntervalMillis));
    }

    public PairingCodeResponse fetchPairingCode(UUID instanceId) {
        ClawInstanceDto instance = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "instance not found"));
        String containerName = containerPrefix + "-" + instance.id();
        LogReadResult lastLogReadResult = null;

        for (int attempt = 1; attempt <= fetchAttempts; attempt++) {
            LogReadResult logReadResult = readContainerLogs(containerName);
            lastLogReadResult = logReadResult;
            if (logReadResult.succeeded()) {
                PairingCodeMatch match = extractPairingCode(logReadResult.output());
                if (match != null) {
                    return new PairingCodeResponse(
                            instance.id(),
                            match.code(),
                            match.sourceLine(),
                            null,
                            Instant.now()
                    );
                }
            }

            if (attempt < fetchAttempts && !sleepBeforeRetry()) {
                return new PairingCodeResponse(
                        instance.id(),
                        null,
                        null,
                        "pairing code fetch interrupted",
                        Instant.now()
                );
            }
        }

        if (lastLogReadResult != null && !lastLogReadResult.succeeded()) {
            return new PairingCodeResponse(
                    instance.id(),
                    null,
                    null,
                    lastLogReadResult.message(),
                    Instant.now()
            );
        }

        return new PairingCodeResponse(
                instance.id(),
                null,
                null,
                "pairing code not found in recent container logs after " + fetchAttempts + " attempts",
                Instant.now()
        );
    }

    private boolean sleepBeforeRetry() {
        try {
            TimeUnit.MILLISECONDS.sleep(fetchRetryInterval.toMillis());
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
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
        PairingCodeMatch numericMatch = findAnyNumericMatch(lines);
        if (numericMatch != null) {
            return numericMatch;
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
            if (!containsHintKeyword(normalized)) {
                continue;
            }

            String hintedCandidate = extractCandidateFromHintLine(line);
            if (StringUtils.hasText(hintedCandidate)) {
                return new PairingCodeMatch(hintedCandidate, line.trim());
            }

            Matcher numericMatcher = NUMERIC_CODE_PATTERN.matcher(line);
            while (numericMatcher.find()) {
                String candidate = numericMatcher.group(1);
                if (isLikelyPairingCode(candidate)) {
                    return new PairingCodeMatch(candidate, line.trim());
                }
            }
        }
        return null;
    }

    private String extractCandidateFromHintLine(String line) {
        Matcher queryMatcher = CODE_QUERY_PATTERN.matcher(line);
        while (queryMatcher.find()) {
            String candidate = sanitizeCandidate(queryMatcher.group(1));
            if (isLikelyPairingCode(candidate)) {
                return candidate;
            }
        }

        Matcher hintedMatcher = HINTED_CODE_PATTERN.matcher(line);
        while (hintedMatcher.find()) {
            String candidate = sanitizeCandidate(hintedMatcher.group(1));
            if (isLikelyPairingCode(candidate)) {
                return candidate;
            }
        }

        Matcher tokenMatcher = ALPHANUMERIC_TOKEN_PATTERN.matcher(line);
        while (tokenMatcher.find()) {
            String candidate = sanitizeCandidate(tokenMatcher.group(1));
            if (isLikelyPairingCode(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean containsHintKeyword(String normalizedLine) {
        for (String keyword : HINT_KEYWORDS) {
            if (normalizedLine.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private PairingCodeMatch findAnyNumericMatch(String[] lines) {
        for (int index = lines.length - 1; index >= 0; index--) {
            String line = lines[index];
            if (!StringUtils.hasText(line)) {
                continue;
            }
            Matcher matcher = NUMERIC_CODE_PATTERN.matcher(line);
            while (matcher.find()) {
                String candidate = matcher.group(1);
                if (isLikelyPairingCode(candidate)) {
                    return new PairingCodeMatch(candidate, line.trim());
                }
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

    private boolean isLikelyPairingCode(String rawCandidate) {
        if (!StringUtils.hasText(rawCandidate)) {
            return false;
        }
        String candidate = sanitizeCandidate(rawCandidate);
        if (!StringUtils.hasText(candidate)) {
            return false;
        }
        String lowerCandidate = candidate.toLowerCase(Locale.ROOT);
        if (CANDIDATE_STOP_WORDS.contains(lowerCandidate)) {
            return false;
        }
        if (DATE_LIKE_PATTERN.matcher(candidate).matches()) {
            return false;
        }

        String normalized = candidate.replace("-", "");
        if (normalized.length() < 4 || normalized.length() > 24) {
            return false;
        }
        if (!normalized.chars().allMatch(Character::isLetterOrDigit)) {
            return false;
        }

        boolean hasDigit = normalized.chars().anyMatch(Character::isDigit);
        boolean hasLetter = normalized.chars().anyMatch(Character::isLetter);
        if (!hasDigit) {
            return hasLetter
                    && normalized.length() >= 6
                    && normalized.equals(normalized.toUpperCase(Locale.ROOT));
        }
        if (!hasLetter) {
            return normalized.length() >= 6 && normalized.length() <= 8;
        }
        return true;
    }

    private String sanitizeCandidate(String candidate) {
        if (!StringUtils.hasText(candidate)) {
            return "";
        }
        return candidate.trim()
                .replaceAll("^[^A-Za-z0-9]+", "")
                .replaceAll("[^A-Za-z0-9]+$", "");
    }

    private record PairingCodeMatch(String code, String sourceLine) {
    }

    private record LogReadResult(boolean succeeded, String output, String message) {
    }
}
