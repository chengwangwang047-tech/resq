package com.resq.mapper;

import com.resq.domain.ComputeResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.Statement;

/** SQL 和结果映射放在 Mapper；Controller/Service 无需处理数据库连接。 */
@Repository
public class JdbcResourceMapper implements ResourceMapper {

    private static final String SELECT_COLUMNS =
            "SELECT id, name, type, status, created_at FROM compute_resource";

    // DATETIME 不包含时区，直接读取为 LocalDateTime，保留表中的本地日期时间。
    private static final RowMapper<ComputeResource> RESOURCE_ROW_MAPPER = (rs, rowNum) ->
            new ComputeResource(
                    rs.getLong("id"),
                    rs.getString("name"),
                    rs.getString("type"),
                    rs.getString("status"),
                    rs.getObject("created_at", LocalDateTime.class));

    private final JdbcTemplate jdbcTemplate;

    public JdbcResourceMapper(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<ComputeResource> findAll() {
        // query 在没有记录时自然返回空列表，不需要特殊的 404 处理。
        return jdbcTemplate.query(SELECT_COLUMNS + " ORDER BY id", RESOURCE_ROW_MAPPER);
    }

    @Override
    public ComputeResource findById(Long id) {
        // ? 使用 PreparedStatement 绑定参数，不把用户输入拼进 SQL。
        List<ComputeResource> resources = jdbcTemplate.query(
                SELECT_COLUMNS + " WHERE id = ?", RESOURCE_ROW_MAPPER, id);
        return resources.isEmpty() ? null : resources.get(0);
    }
    @Override
public void insert(ComputeResource resource) {

    String sql = """
            INSERT INTO compute_resource
            (name, type, status, created_at)
            VALUES (?, ?, ?, ?)
            """;

    KeyHolder keyHolder = new GeneratedKeyHolder();

    jdbcTemplate.update(connection -> {
        var ps = connection.prepareStatement(
                sql,
                Statement.RETURN_GENERATED_KEYS
        );

        ps.setString(1, resource.getName());
        ps.setString(2, resource.getType());
        ps.setString(3, resource.getStatus());
        ps.setObject(4, resource.getCreatedAt());

        return ps;
    }, keyHolder);

    Number generatedId = keyHolder.getKey();

    if (generatedId != null) {
        resource.setId(generatedId.longValue());
    }
}
}
