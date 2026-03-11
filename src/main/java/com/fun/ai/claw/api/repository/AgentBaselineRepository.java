package com.fun.ai.claw.api.repository;

import com.fun.ai.claw.api.model.AgentBaselineRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class AgentBaselineRepository {

    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<AgentBaselineRecord> rowMapper = (rs, rowNum) -> new AgentBaselineRecord(
            rs.getString("agent_key"),
            rs.getString("display_name"),
            rs.getString("description"),
            rs.getString("runtime"),
            rs.getString("source_type"),
            rs.getString("source_ref"),
            rs.getBoolean("enabled"),
            rs.getString("provider"),
            rs.getString("model"),
            rs.getObject("temperature", Double.class),
            rs.getObject("agentic", Boolean.class),
            rs.getString("system_prompt"),
            rs.getString("updated_by"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    public AgentBaselineRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<AgentBaselineRecord> findAll() {
        return jdbcTemplate.query("""
                        select agent_key,
                               display_name,
                               description,
                               runtime,
                               source_type,
                               source_ref,
                               enabled,
                               provider,
                               model,
                               temperature,
                               agentic,
                               system_prompt,
                               updated_by,
                               created_at,
                               updated_at
                        from agent_baseline
                        order by updated_at desc, agent_key asc
                        """,
                rowMapper
        );
    }

    public Optional<AgentBaselineRecord> findByAgentKey(String agentKey) {
        List<AgentBaselineRecord> rows = jdbcTemplate.query("""
                        select agent_key,
                               display_name,
                               description,
                               runtime,
                               source_type,
                               source_ref,
                               enabled,
                               provider,
                               model,
                               temperature,
                               agentic,
                               system_prompt,
                               updated_by,
                               created_at,
                               updated_at
                        from agent_baseline
                        where agent_key = ?
                        """,
                rowMapper,
                agentKey
        );
        return rows.stream().findFirst();
    }

    public boolean existsByAgentKey(String agentKey) {
        Integer result = jdbcTemplate.queryForObject("""
                        select count(1)
                        from agent_baseline
                        where agent_key = ?
                        """,
                Integer.class,
                agentKey
        );
        return result != null && result > 0;
    }

    public void upsert(AgentBaselineRecord record, Instant now) {
        jdbcTemplate.update("""
                        insert into agent_baseline (
                            agent_key,
                            display_name,
                            description,
                            runtime,
                            source_type,
                            source_ref,
                            enabled,
                            provider,
                            model,
                            temperature,
                            agentic,
                            system_prompt,
                            updated_by,
                            created_at,
                            updated_at
                        )
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        on conflict (agent_key) do update
                        set display_name = excluded.display_name,
                            description = excluded.description,
                            runtime = excluded.runtime,
                            source_type = excluded.source_type,
                            source_ref = excluded.source_ref,
                            enabled = excluded.enabled,
                            provider = excluded.provider,
                            model = excluded.model,
                            temperature = excluded.temperature,
                            agentic = excluded.agentic,
                            system_prompt = excluded.system_prompt,
                            updated_by = excluded.updated_by,
                            updated_at = excluded.updated_at
                        """,
                record.agentKey(),
                record.displayName(),
                record.description(),
                record.runtime(),
                record.sourceType(),
                record.sourceRef(),
                record.enabled(),
                record.provider(),
                record.model(),
                record.temperature(),
                record.agentic(),
                record.systemPrompt(),
                record.updatedBy(),
                Timestamp.from(record.createdAt() != null ? record.createdAt() : now),
                Timestamp.from(now)
        );
    }

    public int deleteByAgentKey(String agentKey) {
        return jdbcTemplate.update("delete from agent_baseline where agent_key = ?", agentKey);
    }
}
