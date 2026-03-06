package com.fun.ai.claw.api.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class InstanceAgentGuidanceRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<Row> rowMapper = (rs, rowNum) -> new Row(
            rs.getObject("instance_id", UUID.class),
            rs.getString("agents_md"),
            rs.getBoolean("enabled"),
            rs.getString("updated_by"),
            rs.getTimestamp("updated_at").toInstant()
    );

    public InstanceAgentGuidanceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<Row> findByInstanceId(UUID instanceId) {
        List<Row> rows = jdbcTemplate.query("""
                        select instance_id, agents_md, enabled, updated_by, updated_at
                        from instance_main_prompt
                        where instance_id = ?
                        """,
                rowMapper,
                instanceId
        );
        return rows.stream().findFirst();
    }

    public void upsert(UUID instanceId,
                       String agentsMd,
                       boolean enabled,
                       String updatedBy,
                       Instant updatedAt) {
        jdbcTemplate.update("""
                        insert into instance_main_prompt (instance_id, agents_md, enabled, updated_by, updated_at)
                        values (?, ?, ?, ?, ?)
                        on conflict (instance_id)
                        do update set
                            agents_md = excluded.agents_md,
                            enabled = excluded.enabled,
                            updated_by = excluded.updated_by,
                            updated_at = excluded.updated_at
                        """,
                instanceId,
                agentsMd,
                enabled,
                updatedBy,
                Timestamp.from(updatedAt)
        );
    }

    public int deleteByInstanceId(UUID instanceId) {
        return jdbcTemplate.update("""
                        delete from instance_main_prompt
                        where instance_id = ?
                        """,
                instanceId
        );
    }

    public record Row(
            UUID instanceId,
            String agentsMd,
            boolean enabled,
            String updatedBy,
            Instant updatedAt
    ) {
    }
}
