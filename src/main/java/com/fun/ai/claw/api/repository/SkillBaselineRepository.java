package com.fun.ai.claw.api.repository;

import com.fun.ai.claw.api.model.SkillBaselineRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class SkillBaselineRepository {

    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<SkillBaselineRecord> rowMapper = (rs, rowNum) -> new SkillBaselineRecord(
            rs.getString("skill_key"),
            rs.getString("display_name"),
            rs.getString("description"),
            rs.getString("source_type"),
            rs.getString("source_ref"),
            rs.getBoolean("enabled"),
            rs.getString("updated_by"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    public SkillBaselineRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<SkillBaselineRecord> findAll() {
        return jdbcTemplate.query("""
                        select skill_key,
                               display_name,
                               description,
                               source_type,
                               source_ref,
                               enabled,
                               updated_by,
                               created_at,
                               updated_at
                        from skill_baseline
                        order by updated_at desc, skill_key asc
                        """,
                rowMapper
        );
    }

    public Optional<SkillBaselineRecord> findBySkillKey(String skillKey) {
        List<SkillBaselineRecord> rows = jdbcTemplate.query("""
                        select skill_key,
                               display_name,
                               description,
                               source_type,
                               source_ref,
                               enabled,
                               updated_by,
                               created_at,
                               updated_at
                        from skill_baseline
                        where skill_key = ?
                        """,
                rowMapper,
                skillKey
        );
        return rows.stream().findFirst();
    }

    public boolean existsBySkillKey(String skillKey) {
        Integer result = jdbcTemplate.queryForObject("""
                        select count(1)
                        from skill_baseline
                        where skill_key = ?
                        """,
                Integer.class,
                skillKey
        );
        return result != null && result > 0;
    }

    public boolean existsByDisplayNameIgnoreCase(String displayName) {
        Integer result = jdbcTemplate.queryForObject("""
                        select count(1)
                        from skill_baseline
                        where lower(display_name) = lower(?)
                        """,
                Integer.class,
                displayName
        );
        return result != null && result > 0;
    }

    public boolean existsByDisplayNameIgnoreCaseExcludingSkillKey(String displayName, String skillKey) {
        Integer result = jdbcTemplate.queryForObject("""
                        select count(1)
                        from skill_baseline
                        where lower(display_name) = lower(?)
                          and skill_key <> ?
                        """,
                Integer.class,
                displayName,
                skillKey
        );
        return result != null && result > 0;
    }

    public void upsert(SkillBaselineRecord record, Instant now) {
        jdbcTemplate.update("""
                        insert into skill_baseline (
                            skill_key,
                            display_name,
                            description,
                            source_type,
                            source_ref,
                            enabled,
                            updated_by,
                            created_at,
                            updated_at
                        )
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        on conflict (skill_key) do update
                        set display_name = excluded.display_name,
                            description = excluded.description,
                            source_type = excluded.source_type,
                            source_ref = excluded.source_ref,
                            enabled = excluded.enabled,
                            updated_by = excluded.updated_by,
                            updated_at = excluded.updated_at
                        """,
                record.skillKey(),
                record.displayName(),
                record.description(),
                record.sourceType(),
                record.sourceRef(),
                record.enabled(),
                record.updatedBy(),
                Timestamp.from(record.createdAt() != null ? record.createdAt() : now),
                Timestamp.from(now)
        );
    }

    public int deleteBySkillKey(String skillKey) {
        return jdbcTemplate.update("delete from skill_baseline where skill_key = ?", skillKey);
    }
}
