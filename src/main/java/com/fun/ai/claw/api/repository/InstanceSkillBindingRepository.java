package com.fun.ai.claw.api.repository;

import com.fun.ai.claw.api.model.InstanceSkillBindingRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class InstanceSkillBindingRepository {

    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<InstanceSkillBindingRecord> rowMapper = (rs, rowNum) -> new InstanceSkillBindingRecord(
            rs.getObject("instance_id", UUID.class),
            rs.getString("skill_key"),
            rs.getString("updated_by"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    public InstanceSkillBindingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<InstanceSkillBindingRecord> findByInstanceId(UUID instanceId) {
        return jdbcTemplate.query("""
                        select instance_id,
                               skill_key,
                               updated_by,
                               created_at,
                               updated_at
                        from instance_skill_binding
                        where instance_id = ?
                        order by updated_at desc, skill_key asc
                        """,
                rowMapper,
                instanceId
        );
    }

    public boolean exists(UUID instanceId, String skillKey) {
        Integer result = jdbcTemplate.queryForObject("""
                        select count(1)
                        from instance_skill_binding
                        where instance_id = ?
                          and skill_key = ?
                        """,
                Integer.class,
                instanceId,
                skillKey
        );
        return result != null && result > 0;
    }

    public void upsert(InstanceSkillBindingRecord record, Instant now) {
        jdbcTemplate.update("""
                        insert into instance_skill_binding (
                            instance_id,
                            skill_key,
                            updated_by,
                            created_at,
                            updated_at
                        )
                        values (?, ?, ?, ?, ?)
                        on conflict (instance_id, skill_key) do update
                        set updated_by = excluded.updated_by,
                            updated_at = excluded.updated_at
                        """,
                record.instanceId(),
                record.skillKey(),
                record.updatedBy(),
                Timestamp.from(record.createdAt() != null ? record.createdAt() : now),
                Timestamp.from(now)
        );
    }

    public int delete(UUID instanceId, String skillKey) {
        return jdbcTemplate.update("""
                        delete from instance_skill_binding
                        where instance_id = ?
                          and skill_key = ?
                        """,
                instanceId,
                skillKey
        );
    }

    public List<UUID> findInstanceIdsBySkillKey(String skillKey) {
        return jdbcTemplate.query("""
                        select distinct instance_id
                        from instance_skill_binding
                        where skill_key = ?
                        order by instance_id asc
                        """,
                (rs, rowNum) -> rs.getObject("instance_id", UUID.class),
                skillKey
        );
    }
}
