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
class ReservationMapperTest extends com.resq.support.IsolatedSpringIntegrationTest {

    @Autowired
    private ReservationMapper mapper;

    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

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
        assertEquals(expected.getExpireAt(), actual.getExpireAt());
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
        String suffix = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO `user`(username,password,email,created_at) VALUES(?,?,?,NOW())",
                "mapper-" + suffix, "SYNTHETIC-NO-LOGIN", suffix + "@example.invalid");
        long user = jdbc.queryForObject("SELECT id FROM `user` WHERE username=?", Long.class, "mapper-" + suffix);
        jdbc.update("INSERT INTO compute_resource(name,type,status,created_at) VALUES(?,'GPU','AVAILABLE',NOW())", suffix);
        long resource = jdbc.queryForObject("SELECT id FROM compute_resource WHERE name=?", Long.class, suffix);
        jdbc.update("INSERT INTO resource_slot(resource_id,slot_start,slot_end,status) VALUES(?,?,?,'AVAILABLE')",
                resource, LocalDateTime.of(2099,1,1,9,0), LocalDateTime.of(2099,1,1,10,0));
        long slot = jdbc.queryForObject("SELECT id FROM resource_slot WHERE resource_id=?", Long.class, resource);
        Reservation reservation = new Reservation(null, user, slot, "PENDING", "mapper-" + suffix,
                LocalDateTime.of(2099,1,1,8,0));
        reservation.setExpireAt(LocalDateTime.of(2099,1,1,8,10));
        return reservation;
    }
}
