package com.fun.ai.claw.api.repository;

import com.fun.ai.claw.api.model.ClawInstanceDto;
import com.fun.ai.claw.api.model.InstanceDesiredState;
import com.fun.ai.claw.api.model.InstanceRuntime;
import com.fun.ai.claw.api.model.InstanceStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class InstanceRepository {

    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<ClawInstanceDto> instanceRowMapper = (rs, rowNum) -> new ClawInstanceDto(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            rs.getObject("host_id", UUID.class),
            rs.getString("image"),
            (Integer) rs.getObject("gateway_host_port"),
            null,
            null,
            InstanceRuntime.valueOf(rs.getString("runtime")),
            InstanceStatus.valueOf(rs.getString("status")),
            InstanceDesiredState.valueOf(rs.getString("desired_state")),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    public InstanceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ClawInstanceDto> findAll() {
        return jdbcTemplate.query("""
                        select id, name, host_id, image, gateway_host_port, runtime, status, desired_state, created_at, updated_at
                        from claw_instance
                        order by created_at asc
                        """,
                instanceRowMapper
        );
    }

    public Optional<ClawInstanceDto> findById(UUID instanceId) {
        List<ClawInstanceDto> rows = jdbcTemplate.query("""
                        select id, name, host_id, image, gateway_host_port, runtime, status, desired_state, created_at, updated_at
                        from claw_instance
                        where id = ?
                        """,
                instanceRowMapper,
                instanceId
        );
        return rows.stream().findFirst();
    }

    public boolean existsByNameIgnoreCase(String name) {
        Boolean exists = jdbcTemplate.queryForObject("""
                        select exists(
                            select 1
                            from claw_instance
                            where lower(name) = lower(?)
                        )
                        """,
                Boolean.class,
                name
        );
        return Boolean.TRUE.equals(exists);
    }

    public void insert(ClawInstanceDto instance) {
        jdbcTemplate.update("""
                        insert into claw_instance
                        (id, name, host_id, image, gateway_host_port, runtime, status, desired_state, created_at, updated_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                instance.id(),
                instance.name(),
                instance.hostId(),
                instance.image(),
                instance.gatewayHostPort(),
                instance.runtime().name(),
                instance.status().name(),
                instance.desiredState().name(),
                Timestamp.from(instance.createdAt()),
                Timestamp.from(instance.updatedAt())
        );
    }

    public void updateState(UUID instanceId, InstanceStatus status, InstanceDesiredState desiredState, Instant updatedAt) {
        jdbcTemplate.update("""
                        update claw_instance
                        set status = ?, desired_state = ?, updated_at = ?
                        where id = ?
                        """,
                status.name(),
                desiredState.name(),
                Timestamp.from(updatedAt),
                instanceId
        );
    }

    public List<Integer> findAllocatedGatewayPortsByHostId(UUID hostId) {
        return jdbcTemplate.queryForList("""
                        select gateway_host_port
                        from claw_instance
                        where host_id = ?
                          and gateway_host_port is not null
                        """,
                Integer.class,
                hostId
        );
    }

    public void updateGatewayHostPort(UUID instanceId, int gatewayHostPort, Instant updatedAt) {
        jdbcTemplate.update("""
                        update claw_instance
                        set gateway_host_port = ?, updated_at = ?
                        where id = ?
                        """,
                gatewayHostPort,
                Timestamp.from(updatedAt),
                instanceId
        );
    }

    public int deleteById(UUID instanceId) {
        return jdbcTemplate.update("""
                        delete from claw_instance
                        where id = ?
                        """,
                instanceId
        );
    }
}

