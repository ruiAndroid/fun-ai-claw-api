package com.fun.ai.claw.api.repository;

import com.fun.ai.claw.api.model.OpenClientAppRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class OpenClientAppRepository {

    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<OpenClientAppRecord> rowMapper = (rs, rowNum) -> new OpenClientAppRecord(
            rs.getString("app_id"),
            rs.getString("name"),
            rs.getString("app_secret"),
            rs.getBoolean("enabled"),
            rs.getObject("default_instance_id", UUID.class),
            rs.getString("default_agent_id"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    public OpenClientAppRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<OpenClientAppRecord> findEnabledByAppId(String appId) {
        List<OpenClientAppRecord> rows = jdbcTemplate.query("""
                        select app_id, name, app_secret, enabled, default_instance_id, default_agent_id, created_at, updated_at
                        from open_client_app
                        where app_id = ?
                          and enabled = true
                        """,
                rowMapper,
                appId
        );
        return rows.stream().findFirst();
    }

    public List<OpenClientAppRecord> findAll() {
        return jdbcTemplate.query("""
                        select app_id, name, app_secret, enabled, default_instance_id, default_agent_id, created_at, updated_at
                        from open_client_app
                        order by created_at desc
                        """,
                rowMapper
        );
    }

    public Optional<OpenClientAppRecord> findByAppId(String appId) {
        List<OpenClientAppRecord> rows = jdbcTemplate.query("""
                        select app_id, name, app_secret, enabled, default_instance_id, default_agent_id, created_at, updated_at
                        from open_client_app
                        where app_id = ?
                        """,
                rowMapper,
                appId
        );
        return rows.stream().findFirst();
    }

    public void insert(String appId, String name, String appSecret, boolean enabled,
                       UUID defaultInstanceId, String defaultAgentId, Instant now) {
        jdbcTemplate.update("""
                        insert into open_client_app (app_id, name, app_secret, enabled, default_instance_id, default_agent_id, created_at, updated_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                appId, name, appSecret, enabled,
                defaultInstanceId, defaultAgentId,
                Timestamp.from(now), Timestamp.from(now)
        );
    }

    public int update(String appId, String name, Boolean enabled,
                      UUID defaultInstanceId, String defaultAgentId, Instant now) {
        return jdbcTemplate.update("""
                        update open_client_app
                        set name = coalesce(?, name),
                            enabled = coalesce(?, enabled),
                            default_instance_id = ?,
                            default_agent_id = ?,
                            updated_at = ?
                        where app_id = ?
                        """,
                name, enabled,
                defaultInstanceId, defaultAgentId,
                Timestamp.from(now),
                appId
        );
    }

    public int deleteByAppId(String appId) {
        return jdbcTemplate.update("delete from open_client_app where app_id = ?", appId);
    }
}
