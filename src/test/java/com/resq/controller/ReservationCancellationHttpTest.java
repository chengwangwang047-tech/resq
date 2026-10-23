package com.resq.controller;

import com.resq.domain.Reservation;
import com.resq.dto.ReservationCreateDTO;
import com.resq.mapper.ReservationMapper;
import com.resq.service.JwtService;
import com.resq.service.ReservationService;
import com.resq.support.IsolatedSpringIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doReturn;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReservationCancellationHttpTest extends IsolatedSpringIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ReservationService service;
    @Autowired private JwtService jwt;
    @Autowired private TestRestTemplate http;
    @Autowired private StringRedisTemplate redis;
    @MockitoSpyBean private ReservationMapper mapper;
    private long userId, resourceId, slotId;
    private Reservation original;
    private HttpHeaders headers;

    @BeforeEach void fixture() {
        String suffix = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO `user`(username,password,email,created_at) VALUES(?,?,?,NOW())",
                "cancel-" + suffix, "SYNTHETIC-NO-LOGIN", suffix + "@example.invalid");
        userId = jdbc.queryForObject("SELECT id FROM `user` WHERE username=?", Long.class, "cancel-" + suffix);
        jdbc.update("INSERT INTO compute_resource(name,type,status,created_at) VALUES(?,'GPU','AVAILABLE',NOW())", suffix);
        resourceId = jdbc.queryForObject("SELECT id FROM compute_resource WHERE name=?", Long.class, suffix);
        jdbc.update("INSERT INTO resource_slot(resource_id,slot_start,slot_end,status) VALUES(?,'2099-01-01 09:00:00','2099-01-01 10:00:00','AVAILABLE')", resourceId);
        slotId = jdbc.queryForObject("SELECT id FROM resource_slot WHERE resource_id=?", Long.class, resourceId);
        original = service.create(userId, new ReservationCreateDTO(slotId, UUID.randomUUID().toString()));
        headers = new HttpHeaders();
        headers.setBearerAuth(jwt.generateToken(userId));
    }

    @AfterEach void cleanupOnlyOwnFixtures() {
        if (slotId > 0) {
            jdbc.update("DELETE FROM reservation WHERE slot_id=?", slotId);
            jdbc.update("DELETE FROM resource_slot WHERE id=?", slotId);
        }
        if (resourceId > 0) jdbc.update("DELETE FROM compute_resource WHERE id=?", resourceId);
        if (userId > 0) jdbc.update("DELETE FROM `user` WHERE id=?", userId);
        redis.delete("resource:" + resourceId + ":slots:2099-01-01");
    }

    @Test void repeatedCancellationReturns409AndDoesNotReleaseNewReservation() {
        assertEquals(HttpStatus.OK, cancel().getStatusCode());
        Reservation next = service.create(userId, new ReservationCreateDTO(slotId, UUID.randomUUID().toString()));
        var repeated = cancel();
        assertEquals(HttpStatus.CONFLICT, repeated.getStatusCode());
        assertEquals("CANCELLED", reservationStatus(original.getId()));
        assertEquals("PENDING", reservationStatus(next.getId()));
        assertEquals("RESERVED", slotStatus());
    }

    @Test void confirmedReservationCannotBeCancelled() {
        service.confirm(userId, original.getId());
        assertEquals(HttpStatus.CONFLICT, cancel().getStatusCode());
        assertEquals("CONFIRMED", reservationStatus(original.getId()));
        assertEquals("RESERVED", slotStatus());
    }

    @Test void releaseFailureReturns500AndRollsBackCancellationWithoutEvictingCache() {
        String cacheKey = "resource:" + resourceId + ":slots:2099-01-01";
        redis.opsForValue().set(cacheKey, "synthetic-cache-sentinel");
        doReturn(0).when(mapper).releaseReservedSlot(slotId);
        var result = cancel();
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, result.getStatusCode());
        assertNotNull(result.getBody());
        assertTrue(result.getBody().contains("服务器内部错误，请稍后重试"));
        assertFalse(result.getBody().contains("释放时隙失败"));
        assertEquals("PENDING", reservationStatus(original.getId()));
        assertEquals("RESERVED", slotStatus());
        assertEquals("synthetic-cache-sentinel", redis.opsForValue().get(cacheKey));
    }

    private ResponseEntity<String> cancel() {
        return http.exchange("/api/reservations/" + original.getId(), HttpMethod.DELETE,
                new HttpEntity<>(headers), String.class);
    }
    private String slotStatus() { return jdbc.queryForObject("SELECT status FROM resource_slot WHERE id=?", String.class, slotId); }
    private String reservationStatus(long id) { return jdbc.queryForObject("SELECT status FROM reservation WHERE id=?", String.class, id); }
}
