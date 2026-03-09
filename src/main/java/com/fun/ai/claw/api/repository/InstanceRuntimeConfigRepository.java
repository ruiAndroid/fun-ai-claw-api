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
public class InstanceRuntimeConfigRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<Row> rowMapper = (rs, rowNum) -> new Row(
            rs.getObject("instance_id", UUID.class),
            rs.getString("config_toml"),
            rs.getString("updated_by"),
            rs.getTimestamp("updated_at").toInstant()
    );

    public InstanceRuntimeConfigRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<Row> findByInstanceId(UUID instanceId) {
        List<Row> rows = jdbcTemplate.query("""
                        select instance_id, config_toml, updated_by, updated_at
                        from instance_runtime_config
                        where instance_id = ?
                        """,
                rowMapper,
                instanceId
        );
        return rows.stream().findFirst();
    }

    public void upsert(UUID instanceId,
                       String configToml,
                       String updatedBy,
                       Instant updatedAt) {
        jdbcTemplate.update("""
                        insert into instance_runtime_config (instance_id, config_toml, updated_by, updated_at)
                        values (?, ?, ?, ?)
                        on conflict (instance_id)
                        do update set
                            config_toml = excluded.config_toml,
                            updated_by = excluded.updated_by,
                            updated_at = excluded.updated_at
                        """,
                instanceId,
                configToml,
                updatedBy,
                Timestamp.from(updatedAt)
        );
    }

    public int deleteByInstanceId(UUID instanceId) {
        return jdbcTemplate.update("""
                        delete from instance_runtime_config
                        where instance_id = ?
                        """,
                instanceId
        );
    }

    public record Row(
            UUID instanceId,
            String configToml,
            String updatedBy,
            Instant updatedAt
    ) {
    }
}
