package com.fun.ai.claw.api.repository;

import com.fun.ai.claw.api.model.OpenSessionMessageRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

@Repository
public class OpenSessionMessageRepository {

    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<OpenSessionMessageRecord> rowMapper = (rs, rowNum) -> new OpenSessionMessageRecord(
            rs.getObject("id", UUID.class),
            rs.getObject("session_id", UUID.class),
            rs.getString("event_type"),
            rs.getString("role"),
            rs.getString("content"),
            rs.getString("thinking_content"),
            rs.getString("interaction_json"),
            rs.getString("raw_payload"),
            rs.getString("provider_message_id"),
            (Long) rs.getObject("provider_sequence"),
            rs.getBoolean("pending"),
            rs.getTimestamp("emitted_at") == null ? null : rs.getTimestamp("emitted_at").toInstant(),
            rs.getTimestamp("created_at").toInstant()
    );

    public OpenSessionMessageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(OpenSessionMessageRecord message) {
        jdbcTemplate.update("""
                        insert into open_session_message
                        (id, session_id, event_type, role, content, thinking_content, interaction_json, raw_payload,
                         provider_message_id, provider_sequence, pending, emitted_at, created_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                message.id(),
                message.sessionId(),
                message.eventType(),
                message.role(),
                message.content(),
                message.thinkingContent(),
                message.interactionJson(),
                message.rawPayload(),
                message.providerMessageId(),
                message.providerSequence(),
                message.pending(),
                message.emittedAt() == null ? null : Timestamp.from(message.emittedAt()),
                Timestamp.from(message.createdAt())
        );
    }

    public List<OpenSessionMessageRecord> findBySessionId(UUID sessionId, int limit) {
        int normalizedLimit = Math.min(Math.max(limit, 1), 500);
        return jdbcTemplate.query("""
                        select id, session_id, event_type, role, content, thinking_content, interaction_json, raw_payload,
                               provider_message_id, provider_sequence, pending, emitted_at, created_at
                        from open_session_message
                        where session_id = ?
                        order by created_at asc
                        limit ?
                        """,
                rowMapper,
                sessionId,
                normalizedLimit
        );
    }
}
