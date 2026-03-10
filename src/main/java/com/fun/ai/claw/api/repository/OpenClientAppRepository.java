package com.fun.ai.claw.api.repository;

import com.fun.ai.claw.api.model.OpenClientAppRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class OpenClientAppRepository {

    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<OpenClientAppRecord> rowMapper = (rs, rowNum) -> new OpenClientAppRecord(
            rs.getString("app_id"),
            rs.getString("name"),
            rs.getString("app_secret_hash"),
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
                        select app_id, name, app_secret_hash, enabled, default_instance_id, default_agent_id, created_at, updated_at
                        from open_client_app
                        where app_id = ?
                          and enabled = true
                        """,
                rowMapper,
                appId
        );
        return rows.stream().findFirst();
    }
}
