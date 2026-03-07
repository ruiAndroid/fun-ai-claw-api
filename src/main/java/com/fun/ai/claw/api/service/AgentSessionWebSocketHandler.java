package com.fun.ai.claw.api.service;

import com.fun.ai.claw.api.model.ClawInstanceDto;
import com.fun.ai.claw.api.model.InstanceStatus;
import com.fun.ai.claw.api.repository.InstanceRepository;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.json.JsonParseException;
import org.springframework.boot.json.JsonParser;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.boot.json.JsonWriter;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AgentSessionWebSocketHandler extends TextWebSocketHandler {

    private static final Pattern ANSI_ESCAPE_PATTERN = Pattern.compile("\\u001B\\[[0-9;?]*[ -/]*[@-~]");
    private static final Pattern AGENT_INTERACTION_BLOCK_PATTERN =
            Pattern.compile("(?is)<fun_claw_interaction>\\s*(\\{.*?})\\s*</fun_claw_interaction>");

    private final InstanceRepository instanceRepository;
    private final JsonParser jsonParser = JsonParserFactory.getJsonParser();
    private final JsonWriter<Object> jsonWriter = JsonWriter.standard();
    private final String dockerCommand;
    private final String containerPrefix;
    private final List<String> agentCommandParts;
    private final String lang;
    private final String lcAll;
    private final String rustLog;
    private final Duration processShutdownTimeout;
    private final ExecutorService readerExecutor;
    private final Map<String, AgentSessionContext> contexts = new ConcurrentHashMap<>();

    public AgentSessionWebSocketHandler(InstanceRepository instanceRepository,
                                        @Value("${app.agent-session.docker-command:docker}") String dockerCommand,
                                        @Value("${app.agent-session.container-prefix:funclaw}") String containerPrefix,
                                        @Value("${app.agent-session.command:zeroclaw agent --config-dir /data/zeroclaw}") String agentCommand,
                                        @Value("${app.agent-session.lang:C.UTF-8}") String lang,
                                        @Value("${app.agent-session.lc-all:C.UTF-8}") String lcAll,
                                        @Value("${app.agent-session.rust-log:warn}") String rustLog,
                                        @Value("${app.agent-session.process-shutdown-timeout-seconds:4}") long processShutdownTimeoutSeconds) {
        this.instanceRepository = instanceRepository;
        this.dockerCommand = dockerCommand;
        this.containerPrefix = containerPrefix;
        this.agentCommandParts = parseCommand(agentCommand);
        this.lang = lang;
        this.lcAll = lcAll;
        this.rustLog = rustLog;
        this.processShutdownTimeout = Duration.ofSeconds(Math.max(1, processShutdownTimeoutSeconds));
        this.readerExecutor = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "fun-ai-agent-session-reader");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        WebSocketSession safeSession = new ConcurrentWebSocketSessionDecorator(session, 10_000, 1024 * 1024);
        Optional<UUID> instanceId = parseInstanceId(safeSession);
        if (instanceId.isEmpty()) {
            emitSystemMessage(safeSession, null, "instanceId is required");
            safeClose(safeSession, CloseStatus.BAD_DATA);
            return;
        }

        Optional<ClawInstanceDto> instance = instanceRepository.findById(instanceId.get());
        if (instance.isEmpty()) {
            emitSystemMessage(safeSession, instanceId.get(), "instance not found");
            safeClose(safeSession, CloseStatus.BAD_DATA);
            return;
        }
        if (instance.get().status() != InstanceStatus.RUNNING) {
            emitSystemMessage(safeSession, instanceId.get(), "instance is not running");
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
            emitSystemMessage(safeSession, instanceId.get(), "failed to start agent session: " + ex.getMessage());
            safeClose(safeSession, CloseStatus.SERVER_ERROR);
            return;
        }

        AgentSessionContext context = new AgentSessionContext(
                safeSession,
                process,
                process.getOutputStream(),
                null,
                instanceId.get(),
                containerName
        );
        contexts.put(safeSession.getId(), context);

        Future<?> readerTask = readerExecutor.submit(() -> streamProcessOutput(safeSession.getId()));
        context.setReaderTask(readerTask);

        emitSystemMessage(context, "connected: " + containerName);
        emitSystemMessage(context, "agent session ready: keep this connection open for follow-up interactions");
        emitSystemMessage(context, "tip: reply in the same session to confirm, revise, or continue the current interaction");
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        AgentSessionContext context = contexts.get(session.getId());
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
            emitSystemMessage(context, "write failed: " + ex.getMessage());
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
        AgentSessionContext context = contexts.get(sessionId);
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
                String output = stripAnsiEscapeCodes(new String(buffer, 0, read, StandardCharsets.UTF_8));
                emitDebugFrame(context, output);
                processOutputChunk(context, output);
            }
        } catch (IOException ignored) {
            // Connection/process ended.
        } finally {
            flushBufferedOutput(context);
            safeClose(context.session(), CloseStatus.NORMAL);
        }
    }

    private void processOutputChunk(AgentSessionContext context, String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        String buffered;
        synchronized (context.monitor()) {
            context.lineBuffer().append(chunk);
            buffered = context.lineBuffer().toString();
            context.lineBuffer().setLength(0);
        }
        String[] lines = buffered.split("\n", -1);
        String remainder = lines.length == 0 ? "" : lines[lines.length - 1];
        synchronized (context.monitor()) {
            context.lineBuffer().append(remainder);
        }
        for (int i = 0; i < lines.length - 1; i++) {
            processOutputLine(context, lines[i]);
        }
    }

    private void flushBufferedOutput(AgentSessionContext context) {
        String trailing;
        synchronized (context.monitor()) {
            trailing = context.lineBuffer().toString();
            context.lineBuffer().setLength(0);
        }
        if (StringUtils.hasText(trailing)) {
            processOutputLine(context, trailing);
        }
        finalizePendingAssistantMessage(context);
    }

    private void processOutputLine(AgentSessionContext context, String rawLine) {
        String normalizedLine = rawLine == null ? "" : rawLine.replace("\r", "");
        String trimmedLine = normalizedLine.trim();

        if (!StringUtils.hasText(trimmedLine)) {
            appendAssistantMessageChunk(context, "\n");
            return;
        }

        if (normalizedLine.startsWith("[system]")) {
            finalizePendingAssistantMessage(context);
            String systemContent = normalizedLine.replaceFirst("^\\[system]\\s*", "");
            if (!isInternalSystemMessage(systemContent)) {
                emitSystemMessage(context, systemContent);
            }
            return;
        }

        if ("🦀 ZeroClaw Interactive Mode".equals(trimmedLine) || "Type /help for commands.".equals(trimmedLine)) {
            return;
        }

        if (">".equals(trimmedLine)) {
            finalizePendingAssistantMessage(context);
            return;
        }

        if (trimmedLine.startsWith(">")) {
            String lineAfterPrompt = trimmedLine.substring(1).trim();
            if (!StringUtils.hasText(lineAfterPrompt)) {
                finalizePendingAssistantMessage(context);
                return;
            }
            if (lineAfterPrompt.startsWith("[you]")) {
                return;
            }
            if (isLogLine(lineAfterPrompt) || isMetaLine(lineAfterPrompt)) {
                return;
            }
            appendAssistantMessageChunk(context, lineAfterPrompt + "\n");
            return;
        }

        if (isLogLine(trimmedLine)) {
            if (trimmedLine.contains("turn.complete")) {
                finalizePendingAssistantMessage(context);
            }
            return;
        }

        if (isMetaLine(trimmedLine)) {
            return;
        }

        appendAssistantMessageChunk(context, normalizedLine + "\n");
    }

    private boolean isLogLine(String line) {
        if (!StringUtils.hasText(line)) {
            return false;
        }
        String normalized = line.trim();
        return normalized.contains("zeroclaw::") || normalized.matches("^20\\d{2}-\\d{2}-\\d{2}T.*$");
    }

    private boolean isMetaLine(String line) {
        if (!StringUtils.hasText(line)) {
            return false;
        }
        String normalized = line.trim();
        return normalized.startsWith("The user ")
                || normalized.startsWith("The user's ")
                || normalized.startsWith("The user has ")
                || normalized.startsWith("The user is ")
                || normalized.startsWith("According to ")
                || normalized.startsWith("I need to ")
                || normalized.startsWith("I must ")
                || normalized.startsWith("I should ")
                || normalized.startsWith("Let me ")
                || normalized.startsWith("This is a follow-up")
                || normalized.startsWith("The keywords")
                || normalized.startsWith("The input contains");
    }

    private boolean isInternalSystemMessage(String line) {
        if (!StringUtils.hasText(line)) {
            return false;
        }
        String normalized = line.trim();
        return normalized.startsWith("connected:")
                || normalized.startsWith("agent session ready:")
                || normalized.startsWith("tip:");
    }

    private void appendAssistantMessageChunk(AgentSessionContext context, String chunk) {
        if (chunk == null) {
            return;
        }
        synchronized (context.monitor()) {
            context.pendingAssistantContent().append(chunk);
        }
    }

    private void finalizePendingAssistantMessage(AgentSessionContext context) {
        String pendingContent;
        synchronized (context.monitor()) {
            pendingContent = context.pendingAssistantContent().toString().trim();
            context.pendingAssistantContent().setLength(0);
        }
        if (!StringUtils.hasText(pendingContent)) {
            return;
        }
        ParsedAgentMessage parsedAgentMessage = parseStructuredAgentMessage(pendingContent);
        String displayContent = StringUtils.hasText(parsedAgentMessage.displayContent())
                ? parsedAgentMessage.displayContent().trim()
                : pendingContent;
        emitMessageFrame(context, "assistant", displayContent, false, parsedAgentMessage.interaction());
    }

    private ParsedAgentMessage parseStructuredAgentMessage(String content) {
        String normalized = content == null ? "" : content.replace("\r\n", "\n").trim();
        if (!StringUtils.hasText(normalized)) {
            return new ParsedAgentMessage("", null);
        }
        Matcher matcher = AGENT_INTERACTION_BLOCK_PATTERN.matcher(normalized);
        AgentInteraction interaction = null;
        StringBuffer displayBuffer = new StringBuffer();
        while (matcher.find()) {
            AgentInteraction candidate = parseInteraction(matcher.group(1));
            if (candidate != null && interaction == null) {
                interaction = candidate;
                matcher.appendReplacement(displayBuffer, "");
            } else {
                matcher.appendReplacement(displayBuffer, Matcher.quoteReplacement(matcher.group()));
            }
        }
        matcher.appendTail(displayBuffer);
        String displayContent = displayBuffer.toString().trim();
        if (!StringUtils.hasText(displayContent) && interaction != null && StringUtils.hasText(interaction.title())) {
            displayContent = interaction.title().trim();
        }
        if (!StringUtils.hasText(displayContent)) {
            displayContent = normalized;
        }
        return new ParsedAgentMessage(displayContent, interaction);
    }

    private AgentInteraction parseInteraction(String rawJson) {
        if (!StringUtils.hasText(rawJson)) {
            return null;
        }
        try {
            Map<String, Object> root = jsonParser.parseMap(rawJson);
            String version = textOrDefault(root.get("version"), "1.0");
            String type = textOrNull(root.get("type"));
            String stateId = firstNonBlank(
                    textOrNull(root.get("stateId")),
                    textOrNull(root.get("state_id"))
            );
            String title = textOrNull(root.get("title"));
            List<AgentInteractionAction> actions = new ArrayList<>();
            Object actionsNode = root.get("actions");
            if (actionsNode instanceof List<?> items) {
                for (Object node : items) {
                    AgentInteractionAction action = parseInteractionAction(node);
                    if (action != null) {
                        actions.add(action);
                    }
                }
            }
            if (!StringUtils.hasText(type) || actions.isEmpty()) {
                return null;
            }
            return new AgentInteraction(version, type.trim(), nullIfBlank(stateId), nullIfBlank(title), List.copyOf(actions));
        } catch (JsonParseException ignored) {
            return null;
        }
    }

    private AgentInteractionAction parseInteractionAction(Object node) {
        if (!(node instanceof Map<?, ?> values)) {
            return null;
        }
        String id = textOrNull(values.get("id"));
        String label = textOrNull(values.get("label"));
        String kind = textOrNull(values.get("kind"));
        String payload = textOrNull(values.get("payload"));
        if (!StringUtils.hasText(id)
                || !StringUtils.hasText(label)
                || !StringUtils.hasText(kind)
                || !StringUtils.hasText(payload)) {
            return null;
        }
        String normalizedKind = kind.trim();
        if (!"send".equals(normalizedKind) && !"prefill".equals(normalizedKind)) {
            return null;
        }
        return new AgentInteractionAction(id.trim(), label.trim(), normalizedKind, payload);
    }

    private void emitSystemMessage(AgentSessionContext context, String message) {
        emitMessageFrame(context, "system", message, false, null);
    }

    private void emitSystemMessage(WebSocketSession session, UUID instanceId, String message) {
        AgentSessionContext context = new AgentSessionContext(
                session,
                null,
                null,
                null,
                instanceId,
                null
        );
        emitMessageFrame(context, "system", message, false, null);
    }

    private void emitMessageFrame(AgentSessionContext context,
                                  String role,
                                  String content,
                                  boolean pending,
                                  AgentInteraction interaction) {
        if (context == null || !context.session().isOpen() || !StringUtils.hasText(content)) {
            return;
        }
        long sequence = context.nextSequence();
        Instant emittedAt = Instant.now();
        AgentSessionMessage message = new AgentSessionMessage(
                "1.0",
                context.session().getId() + "-" + sequence,
                context.instanceId(),
                context.session().getId(),
                sequence,
                role,
                content.trim(),
                pending,
                interaction,
                emittedAt.toString()
        );
        AgentSessionFrame frame = new AgentSessionFrame(
                "1.0",
                "message",
                context.instanceId(),
                context.session().getId(),
                message,
                null,
                emittedAt.toString()
        );
        sendFrame(context.session(), frame);
    }

    private void emitDebugFrame(AgentSessionContext context, String chunk) {
        if (context == null || !context.session().isOpen() || chunk == null) {
            return;
        }
        Instant emittedAt = Instant.now();
        AgentSessionFrame frame = new AgentSessionFrame(
                "1.0",
                "debug",
                context.instanceId(),
                context.session().getId(),
                null,
                chunk,
                emittedAt.toString()
        );
        sendFrame(context.session(), frame);
    }

    private void sendFrame(WebSocketSession session, Object payload) {
        if (session == null || !session.isOpen() || payload == null) {
            return;
        }
        try {
            String text = jsonWriter.writeToString(toJsonValue(payload));
            synchronized (session) {
                session.sendMessage(new TextMessage(text));
            }
        } catch (IOException ignored) {
            // Connection may already be closed.
        }
    }

    private Object toJsonValue(Object payload) {
        if (payload instanceof AgentSessionFrame frame) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("version", frame.version());
            values.put("eventType", frame.eventType());
            values.put("instanceId", frame.instanceId() == null ? null : frame.instanceId().toString());
            values.put("sessionId", frame.sessionId());
            if (frame.message() != null) {
                values.put("message", toJsonValue(frame.message()));
            }
            if (frame.chunk() != null) {
                values.put("chunk", frame.chunk());
            }
            values.put("emittedAt", frame.emittedAt());
            return values;
        }
        if (payload instanceof AgentSessionMessage message) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("version", message.version());
            values.put("messageId", message.messageId());
            values.put("instanceId", message.instanceId() == null ? null : message.instanceId().toString());
            values.put("sessionId", message.sessionId());
            values.put("sequence", message.sequence());
            values.put("role", message.role());
            values.put("content", message.content());
            values.put("pending", message.pending());
            if (message.interaction() != null) {
                values.put("interaction", toJsonValue(message.interaction()));
            }
            values.put("emittedAt", message.emittedAt());
            return values;
        }
        if (payload instanceof AgentInteraction interaction) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("version", interaction.version());
            values.put("type", interaction.type());
            if (interaction.stateId() != null) {
                values.put("stateId", interaction.stateId());
            }
            if (interaction.title() != null) {
                values.put("title", interaction.title());
            }
            List<Object> actions = new ArrayList<>();
            for (AgentInteractionAction action : interaction.actions()) {
                actions.add(toJsonValue(action));
            }
            values.put("actions", actions);
            return values;
        }
        if (payload instanceof AgentInteractionAction action) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("id", action.id());
            values.put("label", action.label());
            values.put("kind", action.kind());
            values.put("payload", action.payload());
            return values;
        }
        return payload;
    }

    private List<String> buildExecCommand(String containerName) {
        List<String> command = new ArrayList<>();
        command.add(dockerCommand);
        command.add("exec");
        command.add("-i");
        addContainerEnv(command, "LANG", lang);
        addContainerEnv(command, "LC_ALL", lcAll);
        addContainerEnv(command, "RUST_LOG", rustLog);
        command.add(containerName);
        command.addAll(agentCommandParts);
        return command;
    }

    private void addContainerEnv(List<String> command, String key, String value) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        command.add("-e");
        command.add(key + "=" + value.trim());
    }

    private List<String> parseCommand(String rawCommand) {
        if (!StringUtils.hasText(rawCommand)) {
            throw new IllegalArgumentException("agent session command must not be blank");
        }
        String[] tokens = rawCommand.trim().split("\\s+");
        if (tokens.length == 0) {
            throw new IllegalArgumentException("agent session command must not be blank");
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

    private void safeClose(WebSocketSession session, CloseStatus status) {
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            session.close(status);
        } catch (IOException ignored) {
            // Ignore close failures.
        }
    }

    private String stripAnsiEscapeCodes(String output) {
        return ANSI_ESCAPE_PATTERN.matcher(output).replaceAll("");
    }

    private void cleanup(String sessionId) {
        AgentSessionContext context = contexts.remove(sessionId);
        if (context == null) {
            return;
        }
        try {
            if (context.stdin() != null) {
                context.stdin().close();
            }
        } catch (IOException ignored) {
            // Ignore close failure.
        }
        if (context.process() != null) {
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
        }

        Future<?> readerTask = context.readerTask();
        if (readerTask != null) {
            readerTask.cancel(true);
        }
    }

    private String textOrDefault(Object node, String defaultValue) {
        String value = textOrNull(node);
        return StringUtils.hasText(value) ? value.trim() : defaultValue;
    }

    private String textOrNull(Object node) {
        if (node == null) {
            return null;
        }
        String value = (node instanceof String) ? (String) node : String.valueOf(node);
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private String nullIfBlank(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private record ParsedAgentMessage(String displayContent, AgentInteraction interaction) {
    }

    private record AgentInteraction(String version,
                                    String type,
                                    String stateId,
                                    String title,
                                    List<AgentInteractionAction> actions) {
    }

    private record AgentInteractionAction(String id, String label, String kind, String payload) {
    }

    private record AgentSessionMessage(String version,
                                       String messageId,
                                       UUID instanceId,
                                       String sessionId,
                                       long sequence,
                                       String role,
                                       String content,
                                       boolean pending,
                                       AgentInteraction interaction,
                                       String emittedAt) {
    }

    private record AgentSessionFrame(String version,
                                     String eventType,
                                     UUID instanceId,
                                     String sessionId,
                                     AgentSessionMessage message,
                                     String chunk,
                                     String emittedAt) {
    }

    private static final class AgentSessionContext {
        private final WebSocketSession session;
        private final Process process;
        private final OutputStream stdin;
        private volatile Future<?> readerTask;
        private final UUID instanceId;
        private final String containerName;
        private final Object monitor = new Object();
        private final StringBuilder lineBuffer = new StringBuilder();
        private final StringBuilder pendingAssistantContent = new StringBuilder();
        private long sequence;

        private AgentSessionContext(WebSocketSession session,
                                    Process process,
                                    OutputStream stdin,
                                    Future<?> readerTask,
                                    UUID instanceId,
                                    String containerName) {
            this.session = session;
            this.process = process;
            this.stdin = stdin;
            this.readerTask = readerTask;
            this.instanceId = instanceId;
            this.containerName = containerName;
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

        private UUID instanceId() {
            return instanceId;
        }

        @SuppressWarnings("unused")
        private String containerName() {
            return containerName;
        }

        private Object monitor() {
            return monitor;
        }

        private StringBuilder lineBuffer() {
            return lineBuffer;
        }

        private StringBuilder pendingAssistantContent() {
            return pendingAssistantContent;
        }

        private long nextSequence() {
            synchronized (monitor) {
                sequence += 1;
                return sequence;
            }
        }
    }
}
