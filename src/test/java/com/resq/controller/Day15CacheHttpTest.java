package com.resq.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.resq.domain.ComputeResource;
import com.resq.domain.Reservation;
import com.resq.domain.ResourceSlot;
import com.resq.domain.User;
import com.resq.dto.ReservationCreateDTO;
import com.resq.mapper.ResourceMapper;
import com.resq.mapper.UserMapper;
import com.resq.service.JwtService;
import com.resq.service.ReservationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** 需要真实 MySQL、Redis 与 JWT 环境变量；只写入并清理本次创建的隔离夹具。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class Day15CacheHttpTest {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 6);
    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ResourceMapper resources;
    @Autowired private UserMapper users;
    @Autowired private StringRedisTemplate redis;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtService jwt;
    @Autowired private ReservationService reservations;
    @Autowired private PlatformTransactionManager transactionManager;
    private final List<Long> resourceIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();
    private final List<String> keys = new ArrayList<>();

    private Long resource() {
        var resource = new ComputeResource(null, "D15-HTTP-" + UUID.randomUUID(),
                "GPU", "AVAILABLE", DAY.atStartOfDay());
        resources.insert(resource);
        resourceIds.add(resource.getId());
        keys.add("resource:" + resource.getId());
        return resource.getId();
    }

    private Long slot(Long resourceId, LocalDate day) {
        jdbc.update("INSERT INTO resource_slot (resource_id, slot_start, slot_end, status) VALUES (?, ?, ?, ?)",
                resourceId, day.atTime(9, 0), day.atTime(10, 0), "AVAILABLE");
        return jdbc.queryForObject("SELECT id FROM resource_slot WHERE resource_id = ? AND slot_start = ?",
                Long.class, resourceId, day.atTime(9, 0));
    }

    private Long user() {
        var user = new User();
        String unique = UUID.randomUUID().toString();
        user.setUsername("D15-" + unique);
        user.setEmail(unique + "@example.invalid");
        user.setPasswordHash(new BCryptPasswordEncoder().encode(UUID.randomUUID().toString()));
        user.setCreatedAt(DAY.atStartOfDay());
        users.insert(user);
        userIds.add(user.getId());
        return user.getId();
    }

    private String key(Long resourceId, LocalDate day) {
        String key = "resource:" + resourceId + ":slots:" + day;
        if (!keys.contains(key)) keys.add(key);
        return key;
    }

    private String path(Long resourceId, LocalDate day) {
        return "/api/resources/" + resourceId + "/slots?date=" + day;
    }

    private ResourceSlot[] getSlots(Long resourceId, LocalDate day) {
        var response = http.getForEntity(path(resourceId, day), ResourceSlot[].class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        return response.getBody();
    }

    @AfterEach
    void cleanupOwnFixtures() {
        for (Long id : resourceIds) {
            jdbc.update("DELETE FROM reservation WHERE slot_id IN (SELECT id FROM resource_slot WHERE resource_id = ?)", id);
            jdbc.update("DELETE FROM resource_slot WHERE resource_id = ?", id);
            jdbc.update("DELETE FROM compute_resource WHERE id = ?", id);
        }
        for (Long id : userIds) jdbc.update("DELETE FROM `user` WHERE id = ?", id);
        if (!keys.isEmpty()) redis.delete(keys);
    }

    @Test
    void actualHttpAndRedisSupportMissHitEmptyListExpiryAndCorruptJsonFallback() throws Exception {
        Long resourceId = resource();
        Long slotId = slot(resourceId, DAY);
        slot(resourceId, DAY.plusDays(1));
        String dayKey = key(resourceId, DAY);
        assertFalse(Boolean.TRUE.equals(redis.hasKey(dayKey)));

        long fillStarted = System.nanoTime();
        var first = getSlots(resourceId, DAY);
        assertEquals(1, first.length);
        assertEquals(slotId, first[0].getId());
        assertEquals(DAY.atTime(9, 0), first[0].getSlotStart());
        String cached = redis.opsForValue().get(dayKey);
        assertNotNull(cached);
        assertTrue(cached.contains("2026-10-06T09:00:00"));
        Long ttl = redis.getExpire(dayKey, TimeUnit.SECONDS);
        assertNotNull(ttl);
        long elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - fillStarted) + 1;
        assertTrue(ttl > 0 && ttl >= 300 - elapsedSeconds && ttl <= 360,
                "新缓存 TTL 应为 300–360 秒，扣除本次请求已经耗用的时间；实际 TTL=" + ttl);
        assertEquals(slotId, getSlots(resourceId, DAY)[0].getId());
        assertEquals(cached, redis.opsForValue().get(dayKey));

        LocalDate emptyDay = DAY.plusDays(2);
        String emptyKey = key(resourceId, emptyDay);
        assertEquals(0, getSlots(resourceId, emptyDay).length);
        assertEquals("[]", redis.opsForValue().get(emptyKey));
        assertEquals(0, getSlots(resourceId, emptyDay).length);

        // 只缩短自己的测试键 TTL，验证真实过期和重新回填。
        redis.expire(dayKey, Duration.ofSeconds(1));
        await().atMost(Duration.ofSeconds(4)).until(() -> !Boolean.TRUE.equals(redis.hasKey(dayKey)));
        assertEquals(slotId, getSlots(resourceId, DAY)[0].getId());
        assertTrue(redis.getExpire(dayKey, TimeUnit.SECONDS) > 1);

        redis.opsForValue().set(dayKey, "{broken", Duration.ofMinutes(5));
        assertEquals(slotId, getSlots(resourceId, DAY)[0].getId());
        assertTrue(objectMapper.readTree(redis.opsForValue().get(dayKey)).isArray());

        var all = http.getForEntity("/api/resources/" + resourceId + "/slots", ResourceSlot[].class);
        assertEquals(HttpStatus.OK, all.getStatusCode());
        assertEquals(2, all.getBody().length);
        assertEquals(HttpStatus.BAD_REQUEST,
                http.getForEntity("/api/resources/" + resourceId + "/slots?date=2026-02-30", String.class).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
                http.getForEntity("/api/resources/" + Long.MAX_VALUE + "/slots?date=" + DAY, String.class).getStatusCode());
        System.out.println("DAY15 REAL HTTP/REDIS PASS: MISS/HIT, ISO LocalDateTime, TTL, [], expiry, JSON fallback, legacy, 400, 404");
    }

    @Test
    void actualJwtReservationAndCancelEvictOnlyAffectedDayAndKeepDetailCache() {
        Long resourceId = resource();
        Long slotId = slot(resourceId, DAY);
        slot(resourceId, DAY.plusDays(1));
        Long userId = user();
        String dayKey = key(resourceId, DAY);
        String nextKey = key(resourceId, DAY.plusDays(1));
        getSlots(resourceId, DAY);
        getSlots(resourceId, DAY.plusDays(1));
        String nextCached = redis.opsForValue().get(nextKey);
        String detailCached = redis.opsForValue().get("resource:" + resourceId);

        assertEquals(HttpStatus.UNAUTHORIZED,
                http.getForEntity("/api/reservations/me", String.class).getStatusCode());
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt.generateToken(userId)); // JWT 仅在内存中使用，不输出或保存。
        String requestId = "D15-" + UUID.randomUUID();
        headers.set("X-Request-Id", requestId);
        keys.add("request:" + requestId); // Day18 请求状态也只清理本次夹具自己的键。
        var created = http.exchange("/api/reservations", HttpMethod.POST,
                new HttpEntity<>(new ReservationCreateDTO(slotId, requestId), headers),
                Reservation.class);
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        assertNotNull(created.getBody());
        assertFalse(Boolean.TRUE.equals(redis.hasKey(dayKey)));
        assertEquals(nextCached, redis.opsForValue().get(nextKey));
        assertEquals(detailCached, redis.opsForValue().get("resource:" + resourceId));
        assertEquals("RESERVED", getSlots(resourceId, DAY)[0].getStatus());

        var cancelled = http.exchange("/api/reservations/" + created.getBody().getId(), HttpMethod.DELETE,
                new HttpEntity<>(headers), Reservation.class);
        assertEquals(HttpStatus.OK, cancelled.getStatusCode());
        assertEquals("CANCELLED", cancelled.getBody().getStatus());
        assertFalse(Boolean.TRUE.equals(redis.hasKey(dayKey)));
        assertEquals("AVAILABLE", getSlots(resourceId, DAY)[0].getStatus());
        assertEquals(nextCached, redis.opsForValue().get(nextKey));
        assertEquals(detailCached, redis.opsForValue().get("resource:" + resourceId));
        System.out.println("DAY15 REAL HTTP/REDIS PASS: JWT 401, authenticated create/cancel, exact day invalidation, detail/other day preserved");
    }

    @Test
    void actualDatabaseRollbackKeepsCacheAndRevertsReservationAndSlot() {
        Long resourceId = resource();
        Long slotId = slot(resourceId, DAY);
        Long userId = user();
        String dayKey = key(resourceId, DAY);
        getSlots(resourceId, DAY);
        String before = redis.opsForValue().get(dayKey);
        String requestId = "D15-rollback-" + UUID.randomUUID();
        var transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            reservations.create(userId, new ReservationCreateDTO(slotId, requestId));
            assertEquals(before, redis.opsForValue().get(dayKey), "事务提交前不得删除缓存");
            status.setRollbackOnly();
        });
        assertEquals(before, redis.opsForValue().get(dayKey));
        assertEquals("AVAILABLE", jdbc.queryForObject("SELECT status FROM resource_slot WHERE id = ?", String.class, slotId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM reservation WHERE request_id = ?", Integer.class, requestId));
        System.out.println("DAY15 REAL MYSQL/REDIS PASS: rollback reverts reservation and slot, no cache eviction");
    }
}
