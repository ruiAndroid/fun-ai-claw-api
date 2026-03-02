package com.fun.ai.claw.api.service;

import com.fun.ai.claw.api.model.ClawInstanceDto;
import com.fun.ai.claw.api.model.InstanceStatus;
import com.fun.ai.claw.api.repository.InstanceRepository;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Component
public class TerminalWebSocketHandler extends TextWebSocketHandler {

    private final InstanceRepository instanceRepository;
    private final String dockerCommand;
    private final String containerPrefix;
    private final String shellPath;
    private final List<String> shellCommandParts;
    private final Duration processShutdownTimeout;
    private final ExecutorService readerExecutor;
    private final Map<String, TerminalSessionContext> contexts = new ConcurrentHashMap<>();

    public TerminalWebSocketHandler(InstanceRepository instanceRepository,
                                    @Value("${app.terminal.docker-command:docker}") String dockerCommand,
                                    @Value("${app.terminal.container-prefix:funclaw}") String containerPrefix,
                                    @Value("${app.terminal.shell:/bin/sh}") String shellPath,
                                    @Value("${app.terminal.process-shutdown-timeout-seconds:2}") long processShutdownTimeoutSeconds) {
        this.instanceRepository = instanceRepository;
        this.dockerCommand = dockerCommand;
        this.containerPrefix = containerPrefix;
        this.shellPath = shellPath;
        this.shellCommandParts = parseShellCommand(shellPath);
        this.processShutdownTimeout = Duration.ofSeconds(Math.max(1, processShutdownTimeoutSeconds));
        this.readerExecutor = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "fun-ai-terminal-reader");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        WebSocketSession safeSession = new ConcurrentWebSocketSessionDecorator(session, 10_000, 1024 * 1024);
        Optional<UUID> instanceId = parseInstanceId(safeSession);
        if (instanceId.isEmpty()) {
            sendSystemMessage(safeSession, "instanceId is required");
            safeClose(safeSession, CloseStatus.BAD_DATA);
            return;
        }

        Optional<ClawInstanceDto> instance = instanceRepository.findById(instanceId.get());
        if (instance.isEmpty()) {
            sendSystemMessage(safeSession, "instance not found");
            safeClose(safeSession, CloseStatus.BAD_DATA);
            return;
        }
        if (instance.get().status() != InstanceStatus.RUNNING) {
            sendSystemMessage(safeSession, "instance is not running");
            safeClose(safeSession, CloseStatus.POLICY_VIOLATION);
            return;
        }

        String containerName = containerPrefix + "-" + instanceId.get();
        Process process;
        try {
            process = new ProcessBuilder(buildExecCommand(containerName))
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException ex) {
            sendSystemMessage(safeSession, "failed to start terminal process: " + ex.getMessage());
            safeClose(safeSession, CloseStatus.SERVER_ERROR);
            return;
        }

        TerminalSessionContext context = new TerminalSessionContext(
                safeSession,
                process,
                process.getOutputStream(),
                null
        );
        contexts.put(safeSession.getId(), context);

        Future<?> readerTask = readerExecutor.submit(() -> streamProcessOutput(safeSession.getId()));
        context.setReaderTask(readerTask);

        sendSystemMessage(safeSession, "connected: " + containerName);
        sendSystemMessage(safeSession, "tip: enter command and press Enter");
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        TerminalSessionContext context = contexts.get(session.getId());
        if (context == null) {
            return;
        }

        String payload = message.getPayload();
        if (!StringUtils.hasText(payload)) {
            return;
        }
        try {
            context.stdin().write(payload.getBytes(StandardCharsets.UTF_8));
            context.stdin().flush();
        } catch (IOException ex) {
            sendSystemMessage(context.session(), "write failed: " + ex.getMessage());
            safeClose(context.session(), CloseStatus.SERVER_ERROR);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        cleanup(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        cleanup(session.getId());
    }

    @PreDestroy
    public void shutdown() {
        for (String sessionId : contexts.keySet()) {
            cleanup(sessionId);
        }
        readerExecutor.shutdownNow();
    }

    private void streamProcessOutput(String sessionId) {
        TerminalSessionContext context = contexts.get(sessionId);
        if (context == null) {
            return;
        }
        byte[] buffer = new byte[4096];
        try {
            int read;
            while (context.session().isOpen() && (read = context.process().getInputStream().read(buffer)) != -1) {
                if (read == 0) {
                    continue;
                }
                String output = new String(buffer, 0, read, StandardCharsets.UTF_8);
                synchronized (context.session()) {
                    context.session().sendMessage(new TextMessage(output));
                }
            }
        } catch (IOException ignored) {
            // Connection/process ended.
        } finally {
            safeClose(context.session(), CloseStatus.NORMAL);
        }
    }

    private void cleanup(String sessionId) {
        TerminalSessionContext context = contexts.remove(sessionId);
        if (context == null) {
            return;
        }
        try {
            context.stdin().write("exit\n".getBytes(StandardCharsets.UTF_8));
            context.stdin().flush();
        } catch (IOException ignored) {
            // Ignore and continue process teardown.
        }

        context.process().destroy();
        try {
            boolean exited = context.process().waitFor(processShutdownTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!exited) {
                context.process().destroyForcibly();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            context.process().destroyForcibly();
        }

        Future<?> readerTask = context.readerTask();
        if (readerTask != null) {
            readerTask.cancel(true);
        }
    }

    private List<String> buildExecCommand(String containerName) {
        List<String> command = new ArrayList<>();
        command.add(dockerCommand);
        command.add("exec");
        command.add("-i");
        command.add(containerName);
        command.addAll(shellCommandParts);
        return command;
    }

    private List<String> parseShellCommand(String rawShellCommand) {
        if (!StringUtils.hasText(rawShellCommand)) {
            throw new IllegalArgumentException("terminal shell command must not be blank");
        }
        String[] tokens = rawShellCommand.trim().split("\\s+");
        if (tokens.length == 0) {
            throw new IllegalArgumentException("terminal shell command must not be blank");
        }
        return List.of(tokens);
    }

    private Optional<UUID> parseInstanceId(WebSocketSession session) {
        if (session.getUri() == null) {
            return Optional.empty();
        }
        String rawInstanceId = UriComponentsBuilder.fromUri(session.getUri())
                .build()
                .getQueryParams()
                .getFirst("instanceId");
        if (!StringUtils.hasText(rawInstanceId)) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(rawInstanceId.trim()));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private void sendSystemMessage(WebSocketSession session, String message) {
        if (!session.isOpen()) {
            return;
        }
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage("[system] " + message + "\n"));
            }
        } catch (IOException ignored) {
            // Connection may already be closed.
        }
    }

    private void safeClose(WebSocketSession session, CloseStatus status) {
        if (!session.isOpen()) {
            return;
        }
        try {
            session.close(status);
        } catch (IOException ignored) {
            // Ignore close failures.
        }
    }

    private static final class TerminalSessionContext {
        private final WebSocketSession session;
        private final Process process;
        private final OutputStream stdin;
        private volatile Future<?> readerTask;

        private TerminalSessionContext(WebSocketSession session, Process process, OutputStream stdin, Future<?> readerTask) {
            this.session = session;
            this.process = process;
            this.stdin = stdin;
            this.readerTask = readerTask;
        }

        private WebSocketSession session() {
            return session;
        }

        private Process process() {
            return process;
        }

        private OutputStream stdin() {
            return stdin;
        }

        private Future<?> readerTask() {
            return readerTask;
        }

        private void setReaderTask(Future<?> readerTask) {
            this.readerTask = readerTask;
        }
    }
}

