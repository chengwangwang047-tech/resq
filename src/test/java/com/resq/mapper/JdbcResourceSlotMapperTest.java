package com.resq.mapper;

import com.resq.domain.ComputeResource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class JdbcResourceSlotMapperTest extends com.resq.support.IsolatedSpringIntegrationTest {
    @Autowired private ResourceMapper resources;
    @Autowired private ResourceSlotMapper slots;
    @Autowired private JdbcTemplate jdbc;

    private Long resource() {
        var resource = new ComputeResource(null, "D15-" + UUID.randomUUID(),
                "GPU", "AVAILABLE", LocalDateTime.of(2026, 10, 6, 0, 0));
        resources.insert(resource);
        return resource.getId();
    }

    private void insert(Long resourceId, LocalDateTime start) {
        jdbc.update("INSERT INTO resource_slot (resource_id, slot_start, slot_end, status) VALUES (?, ?, ?, ?)",
                resourceId, start, start.plusHours(1), "AVAILABLE");
    }

    @Test
    void mysqlDateRangeIncludesMidnightExcludesNextDayAndFiltersResource() {
        Long id = resource();
        Long other = resource();
        LocalDate day = LocalDate.of(2026, 10, 6);
        insert(id, day.atTime(12, 0));
        insert(id, day.minusDays(1).atTime(23, 59, 59));
        insert(id, day.atTime(23, 59, 59));
        insert(id, day.atStartOfDay());
        insert(id, day.plusDays(1).atStartOfDay());
        insert(other, day.atTime(9, 0));
        var result = slots.findByResourceIdAndDate(id, day);
        assertEquals(List.of(day.atStartOfDay(), day.atTime(12, 0), day.atTime(23, 59, 59)),
                result.stream().map(slot -> slot.getSlotStart()).toList());
        assertTrue(result.stream().allMatch(slot -> id.equals(slot.getResourceId())));
        assertEquals(5, slots.findByResourceId(id).size());
        var byId = slots.findById(result.get(0).getId());
        assertNotNull(byId);
        assertEquals(id, byId.getResourceId());
        assertEquals(day.atStartOfDay(), byId.getSlotStart());
    }

    @Test
    void emptyDayReturnsEmptyListAndMissingSlotReturnsNull() {
        assertTrue(slots.findByResourceIdAndDate(resource(), LocalDate.of(2026, 10, 6)).isEmpty());
        assertNull(slots.findById(Long.MAX_VALUE));
    }
}
