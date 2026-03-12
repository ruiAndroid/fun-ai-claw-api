package com.fun.ai.claw.api.repository;

import com.fun.ai.claw.api.model.InstanceAgentBindingRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Repository
public class InstanceAgentBindingRepository {

    private static final Pattern JSON_ARRAY_STRING_PATTERN = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"");

    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<InstanceAgentBindingRecord> rowMapper = (rs, rowNum) -> new InstanceAgentBindingRecord(
            rs.getObject("instance_id", UUID.class),
            rs.getString("agent_key"),
            rs.getString("provider"),
            rs.getString("model"),
            rs.getObject("temperature") == null ? null : rs.getDouble("temperature"),
            rs.getObject("agentic") == null ? null : rs.getBoolean("agentic"),
            rs.getString("system_prompt"),
            parseJsonStringArray(rs.getString("allowed_tools_json")),
            rs.getString("extra_config_toml"),
            rs.getString("updated_by"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    public InstanceAgentBindingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<InstanceAgentBindingRecord> findByInstanceId(UUID instanceId) {
        return jdbcTemplate.query("""
                        select instance_id,
                               agent_key,
                               provider,
                               model,
                               temperature,
                               agentic,
                               system_prompt,
                               allowed_tools_json,
                               extra_config_toml,
                               updated_by,
                               created_at,
                               updated_at
                        from instance_agent_binding
                        where instance_id = ?
                        order by updated_at desc, agent_key asc
                        """,
                rowMapper,
                instanceId
        );
    }

    public void upsert(InstanceAgentBindingRecord record, Instant now) {
        jdbcTemplate.update("""
                        insert into instance_agent_binding (
                            instance_id,
                            agent_key,
                            provider,
                            model,
                            temperature,
                            agentic,
                            system_prompt,
                            allowed_tools_json,
                            extra_config_toml,
                            updated_by,
                            created_at,
                            updated_at
                        )
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        on conflict (instance_id, agent_key) do update
                        set provider = excluded.provider,
                            model = excluded.model,
                            temperature = excluded.temperature,
                            agentic = excluded.agentic,
                            system_prompt = excluded.system_prompt,
                            allowed_tools_json = excluded.allowed_tools_json,
                            extra_config_toml = excluded.extra_config_toml,
                            updated_by = excluded.updated_by,
                            updated_at = excluded.updated_at
                        """,
                record.instanceId(),
                record.agentKey(),
                record.provider(),
                record.model(),
                record.temperature(),
                record.agentic(),
                record.systemPrompt(),
                toJsonStringArray(record.allowedTools()),
                record.extraConfigToml(),
                record.updatedBy(),
                Timestamp.from(record.createdAt() != null ? record.createdAt() : now),
                Timestamp.from(now)
        );
    }

    public int delete(UUID instanceId, String agentKey) {
        return jdbcTemplate.update("""
                        delete from instance_agent_binding
                        where instance_id = ?
                          and agent_key = ?
                        """,
                instanceId,
                agentKey
        );
    }

    private List<String> parseJsonStringArray(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        Matcher matcher = JSON_ARRAY_STRING_PATTERN.matcher(json);
        LinkedHashSet<String> values = new LinkedHashSet<>();
        while (matcher.find()) {
            String value = unescapeJsonString(matcher.group(1)).trim();
            if (StringUtils.hasText(value)) {
                values.add(value);
            }
        }
        return List.copyOf(values);
    }

    private String toJsonStringArray(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        List<String> normalized = new ArrayList<>();
        for (String value : values) {
            if (!StringUtils.hasText(value)) {
                continue;
            }
            String trimmed = value.trim();
            if (!trimmed.isEmpty() && !normalized.contains(trimmed)) {
                normalized.add(trimmed);
            }
        }
        if (normalized.isEmpty()) {
            return "[]";
        }
        StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < normalized.size(); index++) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append('"').append(escapeJsonString(normalized.get(index))).append('"');
        }
        builder.append(']');
        return builder.toString();
    }

    private String escapeJsonString(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            switch (ch) {
                case '\\' -> builder.append("\\\\");
                case '"' -> builder.append("\\\"");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> builder.append(ch);
            }
        }
        return builder.toString();
    }

    private String unescapeJsonString(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch != '\\' || index + 1 >= value.length()) {
                builder.append(ch);
                continue;
            }
            char next = value.charAt(++index);
            switch (next) {
                case 'n' -> builder.append('\n');
                case 'r' -> builder.append('\r');
                case 't' -> builder.append('\t');
                case '"' -> builder.append('"');
                case '\\' -> builder.append('\\');
                default -> builder.append(next);
            }
        }
        return builder.toString();
    }
}
