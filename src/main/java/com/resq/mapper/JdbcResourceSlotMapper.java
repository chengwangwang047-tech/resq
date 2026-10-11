package com.resq.mapper;

import com.resq.domain.ResourceSlot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public class JdbcResourceSlotMapper implements ResourceSlotMapper {

    private static final String FIND_BY_RESOURCE_ID = """
            SELECT id, resource_id, slot_start, slot_end, status
            FROM resource_slot
            WHERE resource_id = ?
            ORDER BY slot_start, id
            """;

    private static final String FIND_BY_RESOURCE_ID_AND_DATE = """
            SELECT id, resource_id, slot_start, slot_end, status
            FROM resource_slot
            WHERE resource_id = ? AND slot_start >= ? AND slot_start < ?
            ORDER BY slot_start, id
            """;

    private static final String FIND_BY_ID = """
            SELECT id, resource_id, slot_start, slot_end, status
            FROM resource_slot
            WHERE id = ?
            """;

    // DATETIME 没有时区，沿用资源 Mapper 的 LocalDateTime 映射方式。
    private static final RowMapper<ResourceSlot> SLOT_ROW_MAPPER = (rs, rowNum) ->
            new ResourceSlot(
                    rs.getLong("id"),
                    rs.getLong("resource_id"),
                    rs.getObject("slot_start", LocalDateTime.class),
                    rs.getObject("slot_end", LocalDateTime.class),
                    rs.getString("status"));

    private final JdbcTemplate jdbcTemplate;

    public JdbcResourceSlotMapper(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<ResourceSlot> findByResourceIdAndDate(Long resourceId, LocalDate date) {
        return jdbcTemplate.query(FIND_BY_RESOURCE_ID_AND_DATE, SLOT_ROW_MAPPER,
                resourceId, date.atStartOfDay(), date.plusDays(1).atStartOfDay());
    }

    @Override
    public ResourceSlot findById(Long slotId) {
        List<ResourceSlot> slots = jdbcTemplate.query(FIND_BY_ID, SLOT_ROW_MAPPER, slotId);
        return slots.isEmpty() ? null : slots.get(0);
    }

    @Override
    public List<ResourceSlot> findByResourceId(Long resourceId) {
        return jdbcTemplate.query(FIND_BY_RESOURCE_ID, SLOT_ROW_MAPPER, resourceId);
    }
}
