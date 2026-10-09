package com.resq.mapper;

import com.resq.domain.ComputeResource;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class ResourceXmlLabMapperTest {

    @Autowired
    private ResourceXmlLabMapper mapper;

    @Test
    void insertThenFindByIdMapsAllFieldsAndGeneratedId() {
        ComputeResource expected = fixture();
        assertEquals(1, mapper.insert(expected));
        assertNotNull(expected.getId(), "必须回填自增主键");
        ComputeResource actual = mapper.findById(expected.getId());
        assertNotNull(actual);
        assertEquals(expected.getId(), actual.getId());
        assertEquals(expected.getName(), actual.getName());
        assertEquals(expected.getType(), actual.getType());
        assertEquals(expected.getStatus(), actual.getStatus());
        assertEquals(expected.getCreatedAt(), actual.getCreatedAt());
    }

    @Test
    void findAllContainsTheInsertedFixture() {
        ComputeResource expected = fixture();
        assertEquals(1, mapper.insert(expected));
        assertNotNull(expected.getId());
        assertTrue(mapper.findAll().stream()
                .anyMatch(resource -> expected.getId().equals(resource.getId())));
    }

    @Test
    void missingIdReturnsNull() {
        // 先确认测试库没有该边界 ID；如有则自行换用已核实不存在的正数 ID。
        assertNull(mapper.findById(Long.MAX_VALUE));
    }

    private ComputeResource fixture() {
        return new ComputeResource(null, "D13-" + UUID.randomUUID(), "GPU",
                "AVAILABLE", LocalDateTime.of(2026, 10, 8, 9, 0));
    }
}
