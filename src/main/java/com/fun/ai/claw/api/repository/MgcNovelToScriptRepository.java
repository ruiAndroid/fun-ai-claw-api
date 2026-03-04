package com.fun.ai.claw.api.repository;

import com.fun.ai.claw.api.model.MgcNovelToScriptPrepareRequest;
import com.fun.ai.claw.api.model.MgcNovelToScriptTaskResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MgcNovelToScriptRepository {

    private final JdbcTemplate jdbcTemplate;

    public MgcNovelToScriptRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertPrepared(UUID taskId,
                               String confirmToken,
                               MgcNovelToScriptPrepareRequest request,
                               String requestMessage,
                               Instant expiresAt,
                               Instant now) {
        jdbcTemplate.update("""
                        insert into mgc_novel_to_script_task (
                            task_id, confirm_token, instance_id, script_content, script_type, target_audience, expected_episode_count,
                            status, request_message, expires_at, created_at, updated_at
                        ) values (?, ?, ?, ?, ?, ?, ?, 'PREPARED', ?, ?, ?, ?)
                        """,
                taskId,
                confirmToken,
                request.instanceId(),
                request.scriptContent(),
                request.scriptType(),
                request.targetAudience(),
                request.expectedEpisodeCount(),
                requestMessage,
                Timestamp.from(expiresAt),
                Timestamp.from(now),
                Timestamp.from(now)
        );
    }

    public Optional<TaskRow> claimPrepared(String confirmToken, Instant now) {
        int updated = jdbcTemplate.update("""
                        update mgc_novel_to_script_task
                        set status = 'QUEUED', updated_at = ?
                        where confirm_token = ?
                          and status = 'PREPARED'
                          and expires_at > ?
                        """,
                Timestamp.from(now),
                confirmToken,
                Timestamp.from(now)
        );
        if (updated == 0) {
            return Optional.empty();
        }
        return findByConfirmToken(confirmToken);
    }

    public void markRunning(UUID taskId, Instant now) {
        jdbcTemplate.update("""
                        update mgc_novel_to_script_task
                        set status = 'RUNNING', started_at = ?, updated_at = ?
                        where task_id = ?
                        """,
                Timestamp.from(now),
                Timestamp.from(now),
                taskId
        );
    }

    public void markSucceeded(UUID taskId, String responseBody, Instant now) {
        jdbcTemplate.update("""
                        update mgc_novel_to_script_task
                        set status = 'SUCCEEDED', response_body = ?, error_message = null, finished_at = ?, updated_at = ?
                        where task_id = ?
                        """,
                responseBody,
                Timestamp.from(now),
                Timestamp.from(now),
                taskId
        );
    }

    public void markFailed(UUID taskId, String errorMessage, Instant now) {
        jdbcTemplate.update("""
                        update mgc_novel_to_script_task
                        set status = 'FAILED', error_message = ?, finished_at = ?, updated_at = ?
                        where task_id = ?
                        """,
                errorMessage,
                Timestamp.from(now),
                Timestamp.from(now),
                taskId
        );
    }

    public Optional<MgcNovelToScriptTaskResponse> findTask(UUID taskId) {
        List<MgcNovelToScriptTaskResponse> rows = jdbcTemplate.query("""
                        select task_id, status, response_body, error_message, created_at, updated_at, started_at, finished_at
                        from mgc_novel_to_script_task
                        where task_id = ?
                        """,
                (rs, rowNum) -> new MgcNovelToScriptTaskResponse(
                        rs.getObject("task_id", UUID.class),
                        rs.getString("status"),
                        rs.getString("response_body"),
                        rs.getString("error_message"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant(),
                        rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toInstant(),
                        rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant()
                ),
                taskId
        );
        return rows.stream().findFirst();
    }

    private Optional<TaskRow> findByConfirmToken(String confirmToken) {
        List<TaskRow> rows = jdbcTemplate.query("""
                        select task_id, instance_id, request_message
                        from mgc_novel_to_script_task
                        where confirm_token = ?
                        """,
                (rs, rowNum) -> new TaskRow(
                        rs.getObject("task_id", UUID.class),
                        rs.getObject("instance_id", UUID.class),
                        rs.getString("request_message")
                ),
                confirmToken
        );
        return rows.stream().findFirst();
    }

    public record TaskRow(UUID taskId, UUID instanceId, String requestMessage) {
    }
}

