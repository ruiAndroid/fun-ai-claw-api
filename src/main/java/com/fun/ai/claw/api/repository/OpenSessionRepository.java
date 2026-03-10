package com.fun.ai.claw.api.repository;

import com.fun.ai.claw.api.model.OpenSessionRecord;
import com.fun.ai.claw.api.model.OpenSessionStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class OpenSessionRepository {

    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<OpenSessionRecord> rowMapper = (rs, rowNum) -> new OpenSessionRecord(
            rs.getObject("id", UUID.class),
            rs.getString("app_id"),
            rs.getObject("instance_id", UUID.class),
            rs.getString("agent_id"),
            rs.getString("external_session_key"),
            OpenSessionStatus.valueOf(rs.getString("status")),
            rs.getString("ws_token_hash"),
            rs.getTimestamp("ws_token_expires_at") == null ? null : rs.getTimestamp("ws_token_expires_at").toInstant(),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
            rs.getTimestamp("last_message_at") == null ? null : rs.getTimestamp("last_message_at").toInstant()
    );

    public OpenSessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<OpenSessionRecord> findById(UUID sessionId) {
        List<OpenSessionRecord> rows = jdbcTemplate.query("""
                        select id, app_id, instance_id, agent_id, external_session_key, status,
                               ws_token_hash, ws_token_expires_at, created_at, updated_at, last_message_at
                        from open_session
                        where id = ?
                        """,
                rowMapper,
                sessionId
        );
        return rows.stream().findFirst();
    }

    public Optional<OpenSessionRecord> findByAppIdAndExternalSessionKey(String appId, String externalSessionKey) {
        if (externalSessionKey == null || externalSessionKey.isBlank()) {
            return Optional.empty();
        }
        List<OpenSessionRecord> rows = jdbcTemplate.query("""
                        select id, app_id, instance_id, agent_id, external_session_key, status,
                               ws_token_hash, ws_token_expires_at, created_at, updated_at, last_message_at
                        from open_session
                        where app_id = ?
                          and external_session_key = ?
                        """,
                rowMapper,
                appId,
                externalSessionKey
        );
        return rows.stream().findFirst();
    }

    public void insert(OpenSessionRecord session) {
        jdbcTemplate.update("""
                        insert into open_session
                        (id, app_id, instance_id, agent_id, external_session_key, status,
                         ws_token_hash, ws_token_expires_at, created_at, updated_at, last_message_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                session.id(),
                session.appId(),
                session.instanceId(),
                session.agentId(),
                session.externalSessionKey(),
                session.status().name(),
                session.wsTokenHash(),
                session.wsTokenExpiresAt() == null ? null : Timestamp.from(session.wsTokenExpiresAt()),
                Timestamp.from(session.createdAt()),
                Timestamp.from(session.updatedAt()),
                session.lastMessageAt() == null ? null : Timestamp.from(session.lastMessageAt())
        );
    }

    public void updateWsToken(UUID sessionId, String wsTokenHash, Instant wsTokenExpiresAt, Instant updatedAt) {
        jdbcTemplate.update("""
                        update open_session
                        set ws_token_hash = ?, ws_token_expires_at = ?, updated_at = ?
                        where id = ?
                        """,
                wsTokenHash,
                wsTokenExpiresAt == null ? null : Timestamp.from(wsTokenExpiresAt),
                Timestamp.from(updatedAt),
                sessionId
        );
    }

    public void updateStatus(UUID sessionId, OpenSessionStatus status, Instant updatedAt) {
        jdbcTemplate.update("""
                        update open_session
                        set status = ?, updated_at = ?
                        where id = ?
                        """,
                status.name(),
                Timestamp.from(updatedAt),
                sessionId
        );
    }

    public void touch(UUID sessionId, Instant updatedAt, Instant lastMessageAt) {
        jdbcTemplate.update("""
                        update open_session
                        set updated_at = ?, last_message_at = ?
                        where id = ?
                        """,
                Timestamp.from(updatedAt),
                lastMessageAt == null ? null : Timestamp.from(lastMessageAt),
                sessionId
        );
    }
}
