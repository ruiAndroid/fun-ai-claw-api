package com.fun.ai.claw.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fun.ai.claw.api.config.OpenApiProperties;
import com.fun.ai.claw.api.model.ClawInstanceDto;
import com.fun.ai.claw.api.model.OpenClientAppRecord;
import com.fun.ai.claw.api.model.OpenSessionCreateRequest;
import com.fun.ai.claw.api.model.OpenSessionMessageRecord;
import com.fun.ai.claw.api.model.OpenSessionMessageResponse;
import com.fun.ai.claw.api.model.OpenSessionRecord;
import com.fun.ai.claw.api.model.OpenSessionResponse;
import com.fun.ai.claw.api.model.OpenSessionStatus;
import com.fun.ai.claw.api.repository.InstanceRepository;
import com.fun.ai.claw.api.repository.OpenSessionMessageRepository;
import com.fun.ai.claw.api.repository.OpenSessionRepository;
import org.springframework.boot.json.JsonParseException;
import org.springframework.boot.json.JsonParser;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class OpenSessionService {

    private static final String OPEN_AGENT_SESSION_WS_PATH = "/open/v1/agent-session/ws";

    private final OpenSessionRepository openSessionRepository;
    private final OpenSessionMessageRepository openSessionMessageRepository;
    private final InstanceRepository instanceRepository;
    private final OpenApiAuthService openApiAuthService;
    private final OpenApiProperties openApiProperties;
    private final ObjectMapper objectMapper;
    private final JsonParser jsonParser = JsonParserFactory.getJsonParser();

    public OpenSessionService(OpenSessionRepository openSessionRepository,
                              OpenSessionMessageRepository openSessionMessageRepository,
                              InstanceRepository instanceRepository,
                              OpenApiAuthService openApiAuthService,
                              OpenApiProperties openApiProperties,
                              ObjectMapper objectMapper) {
        this.openSessionRepository = openSessionRepository;
        this.openSessionMessageRepository = openSessionMessageRepository;
        this.instanceRepository = instanceRepository;
        this.openApiAuthService = openApiAuthService;
        this.openApiProperties = openApiProperties;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public OpenSessionResponse createSession(OpenClientAppRecord app, OpenSessionCreateRequest request) {
        OpenSessionCreateRequest normalizedRequest = request == null
                ? new OpenSessionCreateRequest(null, null, null, null, null, null)
                : request;

        UUID resolvedInstanceId = Optional.ofNullable(normalizedRequest.instanceId())
                .orElse(app.defaultInstanceId());
        if (resolvedInstanceId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "instanceId is required");
        }
        ClawInstanceDto instance = instanceRepository.findById(resolvedInstanceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "instance not found"));

        String resolvedAgentId = firstNonBlank(normalizedRequest.agentId(), app.defaultAgentId());
        String normalizedExternalSessionKey = normalizeBlank(normalizedRequest.externalSessionKey());
        Instant now = Instant.now();
        String wsToken = UUID.randomUUID() + UUID.randomUUID().toString().replace("-", "");
        String wsTokenHash = openApiAuthService.hashToken(wsToken);
        Instant wsTokenExpiresAt = now.plusSeconds(Math.max(60, openApiProperties.getSession().getWsTokenTtlSeconds()));

        if (StringUtils.hasText(normalizedExternalSessionKey)) {
            Optional<OpenSessionRecord> existing = openSessionRepository.findByAppIdAndExternalSessionKey(
                    app.appId(),
                    normalizedExternalSessionKey
            );
            if (existing.isPresent()) {
                OpenSessionRecord current = existing.get();
                if (current.status() != OpenSessionStatus.ACTIVE) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "open session already exists but is closed");
                }
                if (!current.instanceId().equals(instance.id())
                        || !equalsNullable(normalizeBlank(current.agentId()), normalizeBlank(resolvedAgentId))) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "externalSessionKey is already bound to another session target");
                }
                openSessionRepository.updateWsToken(current.id(), wsTokenHash, wsTokenExpiresAt, now);
                OpenSessionRecord refreshed = requireSession(current.id());
                return toResponse(refreshed, wsToken);
            }
        }

        OpenSessionRecord session = new OpenSessionRecord(
                UUID.randomUUID(),
                app.appId(),
                instance.id(),
                normalizeBlank(resolvedAgentId),
                normalizeBlank(normalizedRequest.externalUserId()),
                normalizedExternalSessionKey,
                normalizeBlank(normalizedRequest.title()),
                OpenSessionStatus.ACTIVE,
                toJson(normalizedRequest.metadata()),
                wsTokenHash,
                wsTokenExpiresAt,
                now,
                now,
                null
        );
        openSessionRepository.insert(session);
        return toResponse(session, wsToken);
    }

    public OpenSessionResponse getSession(OpenClientAppRecord app, UUID sessionId) {
        return toResponse(requireOwnedSession(app.appId(), sessionId), null);
    }

    public List<OpenSessionMessageResponse> listMessages(OpenClientAppRecord app, UUID sessionId, Integer limit) {
        OpenSessionRecord session = requireOwnedSession(app.appId(), sessionId);
        int normalizedLimit = limit == null ? 100 : limit;
        return openSessionMessageRepository.findBySessionId(session.id(), normalizedLimit).stream()
                .map(this::toMessageResponse)
                .toList();
    }

    @Transactional
    public OpenSessionResponse closeSession(OpenClientAppRecord app, UUID sessionId) {
        OpenSessionRecord session = requireOwnedSession(app.appId(), sessionId);
        Instant now = Instant.now();
        openSessionRepository.updateStatus(session.id(), OpenSessionStatus.CLOSED, now);
        return toResponse(requireSession(session.id()), null);
    }

    public OpenSessionRecord validateWebsocketSession(UUID sessionId, String wsToken) {
        OpenSessionRecord session = requireSession(sessionId);
        if (session.status() != OpenSessionStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "open session is closed");
        }
        if (!openApiAuthService.matchesTokenHash(session.wsTokenHash(), wsToken)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid websocket token");
        }
        if (session.wsTokenExpiresAt() != null && session.wsTokenExpiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "websocket token expired");
        }
        return session;
    }

    @Transactional
    public void recordUserInput(UUID sessionId, String payload) {
        Instant now = Instant.now();
        openSessionMessageRepository.insert(new OpenSessionMessageRecord(
                UUID.randomUUID(),
                sessionId,
                "user_input",
                "user",
                payload == null ? "" : payload,
                null,
                null,
                payload,
                null,
                null,
                false,
                now,
                now
        ));
        openSessionRepository.touch(sessionId, now, now);
    }

    @Transactional
    public void recordRuntimeMessage(UUID sessionId,
                                     String eventType,
                                     String role,
                                     String content,
                                     String thinkingContent,
                                     Object interaction,
                                     String rawPayload,
                                     String providerMessageId,
                                     Long providerSequence,
                                     boolean pending,
                                     Instant emittedAt) {
        Instant createdAt = Instant.now();
        Instant effectiveEmittedAt = emittedAt == null ? createdAt : emittedAt;
        openSessionMessageRepository.insert(new OpenSessionMessageRecord(
                UUID.randomUUID(),
                sessionId,
                normalizeBlank(eventType) == null ? "message" : normalizeBlank(eventType),
                normalizeBlank(role) == null ? "assistant" : normalizeBlank(role),
                content == null ? "" : content,
                normalizeBlank(thinkingContent),
                toJson(interaction),
                rawPayload,
                normalizeBlank(providerMessageId),
                providerSequence,
                pending,
                effectiveEmittedAt,
                createdAt
        ));
        openSessionRepository.touch(sessionId, createdAt, effectiveEmittedAt);
    }

    private OpenSessionRecord requireOwnedSession(String appId, UUID sessionId) {
        OpenSessionRecord session = requireSession(sessionId);
        if (!session.appId().equals(appId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "open session not found");
        }
        return session;
    }

    private OpenSessionRecord requireSession(UUID sessionId) {
        return openSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "open session not found"));
    }

    private OpenSessionResponse toResponse(OpenSessionRecord session, String websocketToken) {
        return new OpenSessionResponse(
                session.id(),
                session.appId(),
                session.instanceId(),
                session.agentId(),
                session.externalUserId(),
                session.externalSessionKey(),
                session.title(),
                session.status(),
                parseJsonMap(session.metadataJson()),
                OPEN_AGENT_SESSION_WS_PATH + "?sessionId=" + session.id()
                        + (StringUtils.hasText(websocketToken) ? "&wsToken=" + websocketToken : ""),
                websocketToken,
                session.wsTokenExpiresAt(),
                session.createdAt(),
                session.updatedAt(),
                session.lastMessageAt()
        );
    }

    private OpenSessionMessageResponse toMessageResponse(OpenSessionMessageRecord message) {
        return new OpenSessionMessageResponse(
                message.id(),
                message.sessionId(),
                message.eventType(),
                message.role(),
                message.content(),
                message.thinkingContent(),
                parseJsonMap(message.interactionJson()),
                message.providerMessageId(),
                message.providerSequence(),
                message.pending(),
                message.emittedAt(),
                message.createdAt(),
                message.rawPayload()
        );
    }

    private Map<String, Object> parseJsonMap(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return jsonParser.parseMap(value);
        } catch (JsonParseException ex) {
            return Map.of("raw", value);
        }
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "failed to serialize open session payload");
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String normalized = normalizeBlank(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private boolean equalsNullable(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private String normalizeBlank(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
