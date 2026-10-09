package com.resq.mapper;

import com.resq.domain.Reservation;
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
class ReservationMapperTest {

    @Autowired
    private ReservationMapper mapper;

    @Test
    void insertThenFindByIdMapsAllColumns() {
        Reservation expected = fixture();
        assertEquals(1, mapper.insert(expected));
        assertNotNull(expected.getId());
        Reservation actual = mapper.findById(expected.getId());
        assertNotNull(actual);
        assertEquals(expected.getId(), actual.getId());
        assertEquals(expected.getUserId(), actual.getUserId());
        assertEquals(expected.getSlotId(), actual.getSlotId());
        assertEquals(expected.getStatus(), actual.getStatus());
        assertEquals(expected.getRequestId(), actual.getRequestId());
        assertEquals(expected.getCreatedAt(), actual.getCreatedAt());
    }

    @Test
    void findByUserIdFiltersAndIncludesFixture() {
        Reservation expected = fixture();
        assertEquals(1, mapper.insert(expected));
        assertNotNull(expected.getId());
        var actual = mapper.findByUserId(expected.getUserId());
        assertTrue(actual.stream().allMatch(row -> expected.getUserId().equals(row.getUserId())));
        assertTrue(actual.stream().anyMatch(row -> expected.getId().equals(row.getId())));
    }

    @Test
    void userWithoutReservationsReturnsEmptyList() {
        // 先在测试库确认此 userId 无预约，或改成你自己的隔离测试用户。
        assertTrue(mapper.findByUserId(Long.MAX_VALUE).isEmpty());
    }

    @Test
    void missingReservationReturnsNull() {
        assertNull(mapper.findById(Long.MAX_VALUE));
    }

    private Reservation fixture() {
        // TODO(TEST-FIXTURE): 先核实测试库 user=1 / slot=1 存在；有其他约束时换隔离夹具。
        // 此 Mapper 测试只验证映射；不宣称实际业务可重复预约同一时隙。
        return new Reservation(null, 1L, 1L, "PENDING", "D13-" + UUID.randomUUID(),
                LocalDateTime.of(2026, 10, 8, 9, 0));
    }
}
