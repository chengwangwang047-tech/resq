package com.resq.controller;

import com.resq.domain.ComputeResource;
import com.resq.domain.ResourceSlot;
import com.resq.mapper.ResourceMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 给此测试应用配置一个无服务的 Redis 端口，不停止用户 Redis。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.data.redis.connect-timeout=100ms", "spring.data.redis.timeout=100ms"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ResourceSlotsRedisFailureHttpTest {
    @Autowired private TestRestTemplate http;
    @Autowired private ResourceMapper resources;
    @Autowired private JdbcTemplate jdbc;
    private Long resourceId;

    @DynamicPropertySource
    static void unavailableRedis(DynamicPropertyRegistry registry) throws IOException {
        int unusedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            unusedPort = socket.getLocalPort();
        }
        registry.add("spring.data.redis.port", () -> unusedPort);
    }

    @AfterEach
    void cleanupOwnFixture() {
        if (resourceId != null) {
            jdbc.update("DELETE FROM resource_slot WHERE resource_id = ?", resourceId);
            jdbc.update("DELETE FROM compute_resource WHERE id = ?", resourceId);
        }
    }

    @Test
    void realRedisConnectionFailureStillReturnsMysqlSlotsAndEmptyListOverHttp() {
        LocalDate day = LocalDate.of(2026, 10, 6);
        var resource = new ComputeResource(null, "D15-fallback-" + UUID.randomUUID(),
                "GPU", "AVAILABLE", day.atStartOfDay());
        resources.insert(resource);
        resourceId = resource.getId();
        jdbc.update("INSERT INTO resource_slot (resource_id, slot_start, slot_end, status) VALUES (?, ?, ?, ?)",
                resourceId, day.atTime(9, 0), day.atTime(10, 0), "AVAILABLE");
        for (int i = 0; i < 2; i++) {
            var response = http.getForEntity("/api/resources/" + resourceId + "/slots?date=" + day, ResourceSlot[].class);
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(1, response.getBody().length);
            assertEquals(day.atTime(9, 0), response.getBody()[0].getSlotStart());
        }
        var empty = http.getForEntity("/api/resources/" + resourceId + "/slots?date=" + day.plusDays(1),
                ResourceSlot[].class);
        assertEquals(HttpStatus.OK, empty.getStatusCode());
        assertEquals(0, empty.getBody().length);
        System.out.println("DAY15 REAL HTTP PASS: unavailable Redis GET/PUT still returns MySQL rows/[] with 200");
    }
}
