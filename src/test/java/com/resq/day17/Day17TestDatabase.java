package com.resq.day17;

import com.resq.cache.ResourceSlotCache;
import com.resq.cache.ResourceSlotCacheInvalidator;
import com.resq.domain.ResourceSlot;
import com.resq.mapper.ReservationMapper;
import com.resq.mapper.ResourceSlotMapper;
import com.resq.service.ReservationService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** No Spring Boot context: never loads application.properties or production connection settings. */
public final class Day17TestDatabase implements AutoCloseable {
    public final HikariDataSource pool;
    public final JdbcTemplate jdbc;
    public final DataSourceTransactionManager tx;
    public final ReservationMapper mapper;
    public final ResourceSlotMapper slots;
    public final ResourceSlotCache cache;
    public final ResourceSlotCacheInvalidator invalidator;
    public final ReservationService service;
    public final RedissonClient redis;
    public static final LocalDateTime START = LocalDateTime.of(2099, 1, 1, 9, 0);

    public Day17TestDatabase() throws Exception {
        String url = System.getProperty("day17.mysql.url", "");
        String port = System.getProperty("day17.redis.port", "");
        String token = System.getProperty("day17.guard", "");
        // Refuse normal/default production addresses before establishing any connection.
        if (!url.matches("jdbc:mysql://127\\.0\\.0\\.1:13317/resq_day17\\?.*")
                || !port.equals("16317") || !token.matches("[a-f0-9]{32}")) {
            throw new IllegalStateException("Use scripts/day17/run_tests.py: dedicated endpoints/guard required");
        }
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(url);
        hc.setUsername("day17_test");
        hc.setPassword(System.getenv().getOrDefault("RESQ_IT_DB_PASSWORD", "")); // Only this temporary loopback instance; no live credentials.
        hc.setMaximumPoolSize(16);
        hc.setMinimumIdle(0);
        hc.setConnectionTimeout(10000);
        hc.setPoolName("day17-isolated");
        pool = new HikariDataSource(hc);
        jdbc = new JdbcTemplate(pool);
        RedissonClient createdRedis = null;
        try {
            assertEquals("resq_day17", jdbc.queryForObject("SELECT DATABASE()", String.class));
            assertEquals(token, jdbc.queryForObject("SELECT token FROM day17_safety_guard", String.class));
            assertEquals("day17_test", jdbc.queryForObject("SELECT SUBSTRING_INDEX(CURRENT_USER(), '@', 1)", String.class));
            assertEquals(13317, jdbc.queryForObject("SELECT @@port", Integer.class));
            Config rc = new Config();
            rc.setThreads(4);
            rc.setNettyThreads(4);
            rc.useSingleServer().setAddress("redis://127.0.0.1:16317")
                    .setConnectionPoolSize(16).setConnectionMinimumIdleSize(1)
                    .setSubscriptionConnectionPoolSize(4).setSubscriptionConnectionMinimumIdleSize(1)
                    .setTimeout(1500);
            createdRedis = Redisson.create(rc);
            redis = createdRedis;
            assertEquals(token, redis.<String>getBucket("day17:safety-guard", org.redisson.client.codec.StringCodec.INSTANCE).get());
            tx = new DataSourceTransactionManager(pool);
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(pool);
            factory.setMapperLocations(new ClassPathResource("mapper/ReservationMapper.xml"));
            var session = new SqlSessionTemplate(factory.getObject());
            mapper = session.getMapper(ReservationMapper.class);
            slots = mock(ResourceSlotMapper.class);
            when(slots.findById(anyLong())).thenAnswer(call -> jdbc.queryForObject(
                    "SELECT id, resource_id, slot_start, slot_end, status FROM resource_slot WHERE id=?",
                    (rs, row) -> new ResourceSlot(rs.getLong(1), rs.getLong(2),
                            rs.getTimestamp(3).toLocalDateTime(), rs.getTimestamp(4).toLocalDateTime(), rs.getString(5)),
                    (Object) call.getArgument(0)));
            cache = mock(ResourceSlotCache.class);
            when(cache.evict(anyLong(), any(LocalDate.class))).thenReturn(true);
            invalidator = new ResourceSlotCacheInvalidator(slots, cache);
            service = transactional(new ReservationService(mapper, invalidator, mock(org.springframework.context.ApplicationEventPublisher.class)));
        } catch (Throwable failure) {
            if (createdRedis != null) createdRedis.shutdown();
            pool.close();
            throw failure;
        }
    }

    public ReservationService transactional(ReservationService target) {
        org.springframework.test.util.ReflectionTestUtils.setField(target, "confirmTimeoutSeconds", 600L);
        ProxyFactory pf = new ProxyFactory(target);
        pf.setProxyTargetClass(true);
        pf.addAdvice(new TransactionInterceptor(tx, new AnnotationTransactionAttributeSource()));
        return (ReservationService) pf.getProxy();
    }

    /** Each scenario owns a distinct fake user, resource and slot; only the private database is used. */
    public Fixture fixture() {
        String suffix = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO `user`(username,password,email,created_at) VALUES(?,?,?,?)",
                "D17-" + suffix, "TEST-ONLY-NO-LOGIN", suffix + "@example.invalid", START);
        long user = jdbc.queryForObject("SELECT id FROM `user` WHERE email=?", Long.class, suffix + "@example.invalid");
        jdbc.update("INSERT INTO compute_resource(name,type,status,created_at) VALUES(?,?,?,?)",
                "D17-" + suffix, "GPU", "AVAILABLE", START);
        long resource = jdbc.queryForObject("SELECT id FROM compute_resource WHERE name=?", Long.class, "D17-" + suffix);
        jdbc.update("INSERT INTO resource_slot(resource_id,slot_start,slot_end,status) VALUES(?,?,?,?)",
                resource, START, START.plusHours(1), "AVAILABLE");
        long slot = jdbc.queryForObject("SELECT id FROM resource_slot WHERE resource_id=?", Long.class, resource);
        return new Fixture(user, resource, slot);
    }

    public record Fixture(long user, long resource, long slot) {}
    public String state(long slot) {
        return jdbc.queryForObject("SELECT status FROM resource_slot WHERE id=?", String.class, slot);
    }
    public int count(long slot) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM reservation WHERE slot_id=?", Integer.class, slot);
    }
    public void cleanup(Fixture f) {
        jdbc.update("DELETE FROM reservation WHERE slot_id=?", f.slot());
        jdbc.update("DELETE FROM resource_slot WHERE id=?", f.slot());
        jdbc.update("DELETE FROM compute_resource WHERE id=?", f.resource());
        jdbc.update("DELETE FROM `user` WHERE id=?", f.user());
        assertEquals(0, count(f.slot()));
    }
    @Override public void close() {
        try { redis.shutdown(); } finally { pool.close(); }
    }
}
