package com.resq.service;

import com.resq.cache.ResourceSlotCacheInvalidator;
import com.resq.domain.Reservation;
import com.resq.dto.ReservationCreateDTO;
import com.resq.exception.ReservationConflictException;
import com.resq.mapper.ReservationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Requires a brand new disposable MySQL instance and full current deployment schema. */
@EnabledIfEnvironmentVariable(named = "RESQ_P0_ISOLATED_MYSQL", matches = "[a-f0-9]{32}")
class ReservationTimeBoundaryIT {
    private JdbcTemplate jdbc;
    private ReservationMapper mapper;
    private ReservationService service;
    private ResourceSlotCacheInvalidator invalidator;
    private ApplicationEventPublisher publisher;
    private long userId;
    private long resourceId;

    @BeforeEach void setUp() throws Exception {
        int port = Integer.parseInt(System.getenv("RESQ_P0_MYSQL_PORT"));
        assertTrue(port > 1024 && port != 3306, "Dedicated loopback port required");
        var dataSource = new DriverManagerDataSource(
                "jdbc:mysql://127.0.0.1:" + port + "/resq_p0?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=UTC",
                "p0_test", System.getenv("RESQ_P0_MYSQL_PASSWORD"));
        jdbc = new JdbcTemplate(dataSource);
        assertEquals(System.getenv("RESQ_P0_ISOLATED_MYSQL"),
                jdbc.queryForObject("SELECT guard FROM p0_safety_guard", String.class));
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setMapperLocations(new ClassPathResource("mapper/ReservationMapper.xml"));
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(ReservationMapper.class);
        invalidator = mock(ResourceSlotCacheInvalidator.class);
        publisher = mock(ApplicationEventPublisher.class);
        var target = new ReservationService(mapper, invalidator, publisher);
        ReflectionTestUtils.setField(target, "confirmTimeoutSeconds", 600L);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource),
                new AnnotationTransactionAttributeSource()));
        service = (ReservationService) proxy.getProxy();
        String suffix = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO `user`(username,password,email,created_at) VALUES(?,?,?,NOW())",
                "p0-" + suffix, "SYNTHETIC-NO-LOGIN", suffix + "@example.invalid");
        userId = jdbc.queryForObject("SELECT id FROM `user` WHERE username=?", Long.class, "p0-" + suffix);
        jdbc.update("INSERT INTO compute_resource(name,type,status,created_at) VALUES(?,'GPU','AVAILABLE',NOW())", suffix);
        resourceId = jdbc.queryForObject("SELECT id FROM compute_resource WHERE name=?", Long.class, suffix);
    }

    @Test void endedAvailableSlotIsRejectedWithoutReservationOrSideEffects() {
        long slot = slot("DATE_SUB(NOW(), INTERVAL 2 HOUR)", "DATE_SUB(NOW(), INTERVAL 1 HOUR)", "AVAILABLE");
        assertThrows(ReservationConflictException.class, () -> create(slot));
        assertEquals("AVAILABLE", state(slot));
        assertEquals(0, count(slot));
        verifyNoInteractions(invalidator, publisher);
    }

    @Test void slotEndingAtDatabaseNowIsRejected() {
        long slot = slot("DATE_SUB(NOW(), INTERVAL 1 HOUR)", "NOW()", "AVAILABLE");
        assertThrows(ReservationConflictException.class, () -> create(slot));
        assertEquals("AVAILABLE", state(slot));
        assertEquals(0, count(slot));
    }

    @Test void futureAvailableSlotStillCreatesPendingReservation() {
        long slot = slot("DATE_ADD(NOW(), INTERVAL 1 DAY)", "DATE_ADD(NOW(), INTERVAL 25 HOUR)", "AVAILABLE");
        Reservation result = create(slot);
        assertEquals("PENDING", result.getStatus());
        assertEquals("RESERVED", state(slot));
        assertEquals(1, count(slot));
        assertTrue(result.getExpireAt().isAfter(result.getCreatedAt()));
        verify(invalidator).evictAfterCommit(slot);
        verify(publisher).publishEvent(any(Object.class));
    }

    @Test void ongoingAvailableSlotKeepsExistingBehavior() {
        long slot = slot("DATE_SUB(NOW(), INTERVAL 1 HOUR)", "DATE_ADD(NOW(), INTERVAL 1 HOUR)", "AVAILABLE");
        assertNotNull(create(slot).getId());
        assertEquals("RESERVED", state(slot));
    }

    @Test void futureReservedSlotStillRejectsConflictingRequest() {
        long slot = slot("DATE_ADD(NOW(), INTERVAL 1 DAY)", "DATE_ADD(NOW(), INTERVAL 25 HOUR)", "RESERVED");
        assertThrows(ReservationConflictException.class, () -> create(slot));
        assertEquals("RESERVED", state(slot));
        assertEquals(0, count(slot));
    }

    @Test void idempotentRetryStillReturnsOriginalAfterSlotEnds() {
        long slot = slot("DATE_ADD(NOW(), INTERVAL 1 DAY)", "DATE_ADD(NOW(), INTERVAL 25 HOUR)", "AVAILABLE");
        String request = UUID.randomUUID().toString();
        Reservation original = service.create(userId, new ReservationCreateDTO(slot, request));
        jdbc.update("UPDATE resource_slot SET slot_end=DATE_SUB(NOW(), INTERVAL 1 HOUR) WHERE id=?", slot);
        assertEquals(original.getId(), service.create(userId, new ReservationCreateDTO(slot, request)).getId());
        assertEquals(1, count(slot));
    }

    @Test void concurrentRequestsCannotReserveAnEndedSlot() throws Exception {
        long slot = slot("DATE_SUB(NOW(), INTERVAL 2 HOUR)", "DATE_SUB(NOW(), INTERVAL 1 HOUR)", "AVAILABLE");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> mapper.reserveSlotIfAvailable(slot));
            var second = pool.submit(() -> mapper.reserveSlotIfAvailable(slot));
            assertEquals(0, first.get(5, TimeUnit.SECONDS));
            assertEquals(0, second.get(5, TimeUnit.SECONDS));
        }
        assertEquals("AVAILABLE", state(slot));
    }

    private Reservation create(long slot) {
        return service.create(userId, new ReservationCreateDTO(slot, UUID.randomUUID().toString()));
    }
    private long slot(String start, String end, String status) {
        // start/end are test-owned constant SQL expressions, never external input.
        jdbc.update("INSERT INTO resource_slot(resource_id,slot_start,slot_end,status) VALUES(?," + start + "," + end + ",?)", resourceId, status);
        return jdbc.queryForObject("SELECT id FROM resource_slot WHERE resource_id=?", Long.class, resourceId);
    }
    private String state(long slot) { return jdbc.queryForObject("SELECT status FROM resource_slot WHERE id=?", String.class, slot); }
    private int count(long slot) { return jdbc.queryForObject("SELECT COUNT(*) FROM reservation WHERE slot_id=?", Integer.class, slot); }
}
