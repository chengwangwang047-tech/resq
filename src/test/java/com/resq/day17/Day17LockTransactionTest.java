
package com.resq.day17;

import com.resq.cache.ReservationRequestStateStore;
import com.resq.domain.Reservation;
import com.resq.dto.ReservationCreateDTO;
import com.resq.exception.ReservationBusyException;
import com.resq.exception.ReservationConflictException;
import com.resq.mapper.ReservationMapper;
import com.resq.service.ReservationLockService;
import com.resq.service.ReservationService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.redisson.config.Config;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Connection;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Day17 事务与 Redisson 锁集成测试。
 *
 * Day18 适配：
 * 1. 注入模拟的 ReservationRequestStateStore。
 * 2. 保留原有数据库事务与锁检查。
 * 3. 重复 requestId 的不同业务请求预期返回业务冲突。
 * 4. 提交后解锁失败时，尝试恢复已成功的预约结果。
 */
@EnabledIfSystemProperty(
        named = "resq.day17.integration",
        matches = "true")
@org.junit.jupiter.api.Tag("integration")
class Day17LockTransactionTest {

    /**
     * Day18 新增：
     * 统一创建测试用 ReservationLockService，
     * 避免每个测试重复设置 Redis 状态模拟。
     */
    private static ReservationLockService day17Service(
            RedissonClient client,
            ReservationService service) {

        ReservationRequestStateStore stateStore =
                mock(ReservationRequestStateStore.class);

        when(stateStore.tryStart(
                anyString(),
                anyLong(),
                anyLong(),
                anyString()))
                .thenReturn(true);

        when(stateStore.markSuccess(
                anyString(),
                anyLong(),
                anyLong(),
                anyString(),
                anyLong()))
                .thenReturn(true);

        return new ReservationLockService(
                client,
                service,
                stateStore, mock(com.resq.service.ReservationRateLimiter.class));
    }

    @Test
    void successCommitsBeforeRealUnlockAndAnotherThreadCanAcquire()
            throws Exception {

        try (Day17TestDatabase db = new Day17TestDatabase()) {

            var f = db.fixture();
            String key = lockKey(f.slot());
            long started = System.nanoTime();

            try (Connection observer = db.pool.getConnection()) {

                assertTrue(observer.getAutoCommit());

                JdbcTemplate external = new JdbcTemplate(
                        new SingleConnectionDataSource(observer, true));

                RLock observedLock = spy(db.redis.getLock(key));

                AtomicBoolean observedCommittedRow =
                        new AtomicBoolean();

                doAnswer(invocation -> {

                    assertFalse(
                            TransactionSynchronizationManager
                                    .isActualTransactionActive(),
                            "Transaction must finish before unlock");

                    assertEquals("RESERVED",
                            external.queryForObject(
                                    "SELECT status FROM resource_slot " +
                                    "WHERE id=?",
                                    String.class,
                                    f.slot()));

                    assertEquals(1,
                            external.queryForObject(
                                    "SELECT COUNT(*) FROM reservation " +
                                    "WHERE slot_id=? AND status='PENDING'",
                                    Integer.class,
                                    f.slot()));

                    observedCommittedRow.set(true);

                    return invocation.callRealMethod();

                }).when(observedLock).unlock();

                ReservationLockService service =
                        withObservedLock(
                                db.service,
                                key,
                                observedLock);

                Reservation created =
                        service.create(f.user(), request(f.slot()));

                assertNotNull(created.getId());
                assertTrue(observedCommittedRow.get());

                verify(observedLock)
                        .tryLock(2, TimeUnit.SECONDS);

                verify(observedLock).unlock();

                assertEquals(1, db.count(f.slot()));

                assertReleasedForAnotherThread(db, key);

                report(
                        "success_commit_before_unlock",
                        started, db, f.slot());

            } finally {
                releaseMainThreadLock(db, key);
                db.cleanup(f);
            }
        }
    }

    @Test
    void actualBusinessConflictPreservesExistingReservationAndReleasesLock()
            throws Exception {

        try (Day17TestDatabase db = new Day17TestDatabase()) {

            var f = db.fixture();
            String key = lockKey(f.slot());
            long started = System.nanoTime();

            try {
                ReservationLockService service =
                        day17Service(db.redis, db.service);

                service.create(f.user(), request(f.slot()));

                // 新 requestId，仍然竞争已经占用的时隙
                assertThrows(
                        ReservationConflictException.class,
                        () -> service.create(
                                f.user(), request(f.slot())));

                assertEquals("RESERVED", db.state(f.slot()));
                assertEquals(1, db.count(f.slot()));

                assertReleasedForAnotherThread(db, key);

                report(
                        "business_conflict_release",
                        started, db, f.slot());

            } finally {
                releaseMainThreadLock(db, key);
                db.cleanup(f);
            }
        }
    }

    /**
     * Day18 调整：
     * 相同 requestId 不能被用于不同的 slotId。
     * 新的 Service 会提前识别并抛出业务冲突。
     */
    @Test
    void reusedRequestIdDifferentSlotReturnsConflictAndPreservesSlot()
            throws Exception {

        try (Day17TestDatabase db = new Day17TestDatabase()) {

            var first = db.fixture();
            var second = db.fixture();

            String firstKey = lockKey(first.slot());
            String secondKey = lockKey(second.slot());

            long started = System.nanoTime();

            try {
                ReservationLockService service =
                        day17Service(db.redis, db.service);

                String duplicateRequest =
                        "day17-duplicate-" + UUID.randomUUID();

                Reservation created = service.create(
                        first.user(),
                        new ReservationCreateDTO(
                                first.slot(), duplicateRequest));

                assertNotNull(created.getId());

                // 相同 requestId，但换了另一个时隙
                assertThrows(
                        ReservationConflictException.class,
                        () -> service.create(
                                first.user(),
                                new ReservationCreateDTO(
                                        second.slot(),
                                        duplicateRequest)));

                assertEquals(
                        "RESERVED", db.state(first.slot()));

                assertEquals(1, db.count(first.slot()));

                // 冲突请求不应占用第二个时隙
                assertEquals(
                        "AVAILABLE", db.state(second.slot()));

                assertEquals(0, db.count(second.slot()));

                assertReleasedForAnotherThread(db, secondKey);

                // 使用新的 requestId，可以正常预约第二个时隙
                Reservation retry = service.create(
                        first.user(),
                        request(second.slot()));

                assertNotNull(retry.getId());

                assertEquals(
                        "RESERVED", db.state(second.slot()));

                assertEquals(1, db.count(second.slot()));

                assertReleasedForAnotherThread(db, secondKey);

                report(
                        "request_id_conflict_then_new_request",
                        started, db, second.slot());

            } finally {
                releaseMainThreadLock(db, firstKey);
                releaseMainThreadLock(db, secondKey);

                db.cleanup(second);
                db.cleanup(first);
            }
        }
    }

    @Test
    void simulatedDatabaseTransportFailureAfterRealUpdateRollsBackAndReleasesLock()
            throws Exception {

        try (Day17TestDatabase db = new Day17TestDatabase()) {

            var f = db.fixture();
            String key = lockKey(f.slot());

            long started = System.nanoTime();

            try {
                ReservationMapper failingMapper = spy(db.mapper);

                DataAccessResourceFailureException failure =
                        new DataAccessResourceFailureException(
                                "Day17 simulated database transport failure");

                AtomicBoolean realUpdateReached =
                        new AtomicBoolean();

                doAnswer(invocation -> {

                    assertTrue(
                            TransactionSynchronizationManager
                                    .isActualTransactionActive());

                    assertEquals(
                            "RESERVED",
                            db.state(f.slot()));

                    realUpdateReached.set(true);

                    throw failure;

                }).when(failingMapper)
                        .insert(any(Reservation.class));

                ReservationService transactional =
                        db.transactional(
                                new ReservationService(
                                        failingMapper,
                                        db.invalidator, mock(org.springframework.context.ApplicationEventPublisher.class)));

                ReservationLockService service =
                        day17Service(db.redis, transactional);

                assertSame(
                        failure,
                        assertThrows(
                                DataAccessResourceFailureException.class,
                                () -> service.create(
                                        f.user(),
                                        request(f.slot()))));

                assertTrue(realUpdateReached.get());

                assertEquals(
                        "AVAILABLE", db.state(f.slot()));

                assertEquals(0, db.count(f.slot()));

                verify(db.cache, never())
                        .evict(anyLong(), any(LocalDate.class));

                assertReleasedForAnotherThread(db, key);

                report(
                        "injected_database_failure_rollback",
                        started, db, f.slot());

            } finally {
                releaseMainThreadLock(db, key);
                db.cleanup(f);
            }
        }
    }

    @Test
    void realAcquisitionWaitTimesOutNearTwoSecondsAndKeepsOwnersLock()
            throws Exception {

        try (Day17TestDatabase db = new Day17TestDatabase()) {

            var f = db.fixture();
            String key = lockKey(f.slot());

            RLock owner = db.redis.getLock(key);

            ExecutorService worker =
                    Executors.newSingleThreadExecutor();

            try {
                owner.lock();

                RLock contender = spy(db.redis.getLock(key));

                ReservationLockService service =
                        withObservedLock(
                                db.service, key, contender);

                Future<Outcome> future = worker.submit(
                        () -> capture(
                                service,
                                f.user(),
                                request(f.slot())));

                Outcome result =
                        future.get(8, TimeUnit.SECONDS);

                assertNotNull(result.failure());

                assertEquals(
                        ReservationBusyException.class,
                        result.failure().getClass());

                assertTrue(
                        result.elapsedMillis() >= 1700
                        && result.elapsedMillis() <= 6000,
                        "Expected approximately 2-second wait");

                assertFalse(result.interrupted());

                assertTrue(owner.isHeldByCurrentThread());

                verify(contender)
                        .tryLock(2, TimeUnit.SECONDS);

                verify(contender, never()).unlock();

                assertEquals(
                        "AVAILABLE", db.state(f.slot()));

                assertEquals(0, db.count(f.slot()));

                owner.unlock();

                assertReleasedForAnotherThread(db, key);

            } finally {
                stop(worker);
                releaseMainThreadLock(db, key);
                db.cleanup(f);
            }
        }
    }

    @Test
    void realWaitingThreadInterruptionRestoresFlagWithoutReleasingOwnersLock()
            throws Exception {

        try (Day17TestDatabase db = new Day17TestDatabase()) {

            var f = db.fixture();
            String key = lockKey(f.slot());

            RLock owner = db.redis.getLock(key);

            ExecutorService worker =
                    Executors.newSingleThreadExecutor();

            try {
                owner.lock();

                RLock contender = spy(db.redis.getLock(key));

                CountDownLatch enteredWait =
                        new CountDownLatch(1);

                doAnswer(invocation -> {

                    enteredWait.countDown();
                    return invocation.callRealMethod();

                }).when(contender)
                        .tryLock(2, TimeUnit.SECONDS);

                ReservationLockService service =
                        withObservedLock(
                                db.service, key, contender);

                AtomicReference<Thread> waitingThread =
                        new AtomicReference<>();

                Future<Outcome> future = worker.submit(() -> {

                    waitingThread.set(Thread.currentThread());

                    return capture(
                            service,
                            f.user(),
                            request(f.slot()));
                });

                assertTrue(
                        enteredWait.await(5, TimeUnit.SECONDS));

                await()
                        .pollDelay(Duration.ZERO)
                        .pollInterval(Duration.ofMillis(5))
                        .atMost(Duration.ofMillis(1500))
                        .until(() -> isRedissonSemaphoreWait(
                                waitingThread.get()));

                waitingThread.get().interrupt();

                Outcome result =
                        future.get(5, TimeUnit.SECONDS);

                assertInstanceOf(
                        ReservationBusyException.class,
                        result.failure());

                assertInstanceOf(
                        InterruptedException.class,
                        result.failure().getCause());

                assertTrue(result.interrupted());

                assertTrue(owner.isHeldByCurrentThread());

                verify(contender)
                        .tryLock(2, TimeUnit.SECONDS);

                verify(contender, never()).unlock();

                assertEquals(
                        "AVAILABLE", db.state(f.slot()));

                assertEquals(0, db.count(f.slot()));

                owner.unlock();

                assertReleasedForAnotherThread(db, key);

            } finally {
                stop(worker);
                releaseMainThreadLock(db, key);
                db.cleanup(f);
            }
        }
    }

    @Test
    void actualPreInterruptedRedisCommandReportsLibraryFailureAndPreservesOwnersLock()
            throws Exception {

        try (Day17TestDatabase db = new Day17TestDatabase()) {

            var f = db.fixture();
            String key = lockKey(f.slot());

            RLock owner = db.redis.getLock(key);

            ExecutorService worker =
                    Executors.newSingleThreadExecutor();

            try {
                owner.lock();

                RLock contender = spy(db.redis.getLock(key));

                doAnswer(invocation -> {

                    Thread.currentThread().interrupt();
                    return invocation.callRealMethod();

                }).when(contender)
                        .tryLock(2, TimeUnit.SECONDS);

                ReservationLockService service =
                        withObservedLock(
                                db.service, key, contender);

                Outcome result = worker.submit(
                        () -> capture(
                                service,
                                f.user(),
                                request(f.slot())))
                        .get(5, TimeUnit.SECONDS);

                assertInstanceOf(
                        RedisException.class,
                        result.failure());

                assertTrue(owner.isHeldByCurrentThread());

                verify(contender)
                        .tryLock(2, TimeUnit.SECONDS);

                verify(contender, never()).unlock();

                assertEquals(
                        "AVAILABLE", db.state(f.slot()));

                assertEquals(0, db.count(f.slot()));

                System.out.printf(
                        "DAY17 PATH preinterrupted " +
                        "cause_chain=%s interrupted=%s%n",
                        causeChain(result.failure()),
                        result.interrupted());

                owner.unlock();

                assertReleasedForAnotherThread(db, key);

            } finally {
                stop(worker);
                releaseMainThreadLock(db, key);
                db.cleanup(f);
            }
        }
    }

    @Test
    void cacheEvictionFailureAfterCommitKeepsReservationAndRetriesAfterLockRelease()
            throws Exception {

        try (Day17TestDatabase db = new Day17TestDatabase()) {

            var f = db.fixture();
            String key = lockKey(f.slot());

            long started = System.nanoTime();

            try {
                when(db.cache.evict(
                        anyLong(),
                        any(LocalDate.class)))
                        .thenReturn(false, true);

                ReservationLockService service =
                        day17Service(db.redis, db.service);

                Reservation created =
                        service.create(
                                f.user(),
                                request(f.slot()));

                assertNotNull(created.getId());

                assertEquals(
                        "RESERVED", db.state(f.slot()));

                assertEquals(1, db.count(f.slot()));

                verify(db.cache, times(1))
                        .evict(
                                f.resource(),
                                Day17TestDatabase.START.toLocalDate());

                assertReleasedForAnotherThread(db, key);

                db.invalidator.retryFailedEvictions();

                verify(db.cache, times(2))
                        .evict(
                                f.resource(),
                                Day17TestDatabase.START.toLocalDate());

                db.invalidator.retryFailedEvictions();

                verify(db.cache, times(2))
                        .evict(
                                f.resource(),
                                Day17TestDatabase.START.toLocalDate());

                assertEquals(1, db.count(f.slot()));

                report(
                        "cache_failure_after_commit_then_retry",
                        started, db, f.slot());

            } finally {
                releaseMainThreadLock(db, key);
                db.cleanup(f);
            }
        }
    }

    /**
     * Day18 调整：
     * MySQL 已提交后，Redisson 解锁失败，
     * 现在应尽可能回查并恢复成功结果。
     */
    @Test
    void actualClosedRedisClientCleanupFailureCanRecoverCommittedReservation()
            throws Exception {

        try (Day17TestDatabase db = new Day17TestDatabase()) {

            var f = db.fixture();
            String key = lockKey(f.slot());

            RedissonClient isolatedClient = null;
            long started = System.nanoTime();

            try {
                Config config = new Config();

                config.setThreads(4);
                config.setNettyThreads(4);
                config.setLockWatchdogTimeout(2000);

                config.useSingleServer()
                        .setAddress("redis://127.0.0.1:16317")
                        .setConnectionPoolSize(4)
                        .setConnectionMinimumIdleSize(1)
                        .setSubscriptionConnectionPoolSize(2)
                        .setSubscriptionConnectionMinimumIdleSize(1)
                        .setTimeout(1500);

                isolatedClient = Redisson.create(config);

                RedissonClient disposable = isolatedClient;

                ReservationCreateDTO dto = request(f.slot());

                ReservationService delegate =
                        mock(ReservationService.class);

                // Day18：第一次历史查询为空，提交后可回查成功记录
                when(delegate.findExistingForRetry(
                        eq(f.user()), same(dto)))
                        .thenAnswer(invocation ->
                                db.service.findExistingForRetry(
                                        f.user(), dto));

                when(delegate.create(
                        eq(f.user()), same(dto)))
                        .thenAnswer(invocation -> {

                            Reservation committed =
                                    db.service.create(f.user(), dto);

                            // 模拟事务提交后 Redisson 客户端关闭
                            disposable.shutdown(
                                    0, 100, TimeUnit.MILLISECONDS);

                            return committed;
                        });

                ReservationLockService service =
                        day17Service(disposable, delegate);

                Reservation created =
                        service.create(f.user(), dto);

                assertNotNull(created.getId());

                verify(delegate, atLeastOnce())
                        .create(f.user(), dto);

                assertEquals(
                        "RESERVED", db.state(f.slot()));

                assertEquals(1, db.count(f.slot()));

                RLock observer = db.redis.getLock(key);

                assertTrue(observer.isLocked());

                long remainingTtl =
                        observer.remainTimeToLive();

                assertTrue(
                        remainingTtl > 0
                        && remainingTtl <= 2000);

                long expiryStarted = System.nanoTime();

                await()
                        .pollInterval(Duration.ofMillis(50))
                        .atMost(Duration.ofSeconds(6))
                        .until(() -> !observer.isLocked());

                long expiryMillis =
                        TimeUnit.NANOSECONDS.toMillis(
                                System.nanoTime() - expiryStarted);

                assertReleasedForAnotherThread(db, key);

                assertEquals(1, db.count(f.slot()));

                System.out.printf(
                        "DAY18 recovery " +
                        "elapsed_ms=%d " +
                        "ttl_at_failure_ms=%d " +
                        "ttl_release_ms=%d " +
                        "committed=true%n",
                        TimeUnit.NANOSECONDS.toMillis(
                                System.nanoTime() - started),
                        remainingTtl,
                        expiryMillis);

            } finally {
                if (isolatedClient != null
                        && !isolatedClient.isShutdown()) {

                    isolatedClient.shutdown(
                            0, 100, TimeUnit.MILLISECONDS);
                }

                await()
                        .pollInterval(Duration.ofMillis(50))
                        .atMost(Duration.ofSeconds(6))
                        .until(() ->
                                !db.redis.getLock(key).isLocked());

                db.cleanup(f);
            }
        }
    }

    // ===== 以下是测试辅助方法 =====

    private static ReservationLockService withObservedLock(
            ReservationService transactional,
            String key,
            RLock observed) {

        RedissonClient client =
                mock(RedissonClient.class);

        when(client.getLock(key))
                .thenReturn(observed);

        return day17Service(client, transactional);
    }

    private static ReservationCreateDTO request(long slot) {

        return new ReservationCreateDTO(
                slot,
                "day17-integration-" + UUID.randomUUID());
    }

    private static String lockKey(long slot) {

        return "resq:lock:slot:" + slot;
    }

    private static void assertReleasedForAnotherThread(
            Day17TestDatabase db,
            String key) throws Exception {

        ExecutorService worker =
                Executors.newSingleThreadExecutor();

        try {
            Future<Boolean> acquired = worker.submit(() -> {

                RLock lock = db.redis.getLock(key);

                boolean owned =
                        lock.tryLock(1, TimeUnit.SECONDS);

                try {
                    return owned
                            && lock.isHeldByCurrentThread();

                } finally {
                    if (owned && lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                }
            });

            assertTrue(
                    acquired.get(5, TimeUnit.SECONDS),
                    "A different thread must acquire the released lock");

        } finally {
            stop(worker);
        }
    }

    private static Outcome capture(
            ReservationLockService service,
            long user,
            ReservationCreateDTO dto) {

        long started = System.nanoTime();

        Throwable failure = null;

        try {
            service.create(user, dto);

        } catch (Throwable caught) {
            failure = caught;
        }

        boolean interrupted =
                Thread.currentThread().isInterrupted();

        // 保存中断结果后清除线程中断标记
        Thread.interrupted();

        long elapsed =
                TimeUnit.NANOSECONDS.toMillis(
                        System.nanoTime() - started);

        return new Outcome(
                failure, interrupted, elapsed);
    }

    private record Outcome(
            Throwable failure,
            boolean interrupted,
            long elapsedMillis) {}

    private static boolean isRedissonSemaphoreWait(Thread thread) {

        if (thread == null) {
            return false;
        }

        boolean insideRedissonLock = false;
        boolean semaphoreWait = false;

        for (StackTraceElement frame : thread.getStackTrace()) {

            if (frame.getClassName()
                    .equals("org.redisson.RedissonLock")
                    && frame.getMethodName().equals("tryLock")) {

                insideRedissonLock = true;
            }

            if ((frame.getClassName()
                    .equals("java.util.concurrent.Semaphore")
                    && frame.getMethodName()
                            .equals("tryAcquire"))

                    || (frame.getClassName()
                            .equals("java.util.concurrent.locks.AbstractQueuedSynchronizer")
                    && frame.getMethodName()
                            .equals("tryAcquireSharedNanos"))) {

                semaphoreWait = true;
            }
        }

        return insideRedissonLock && semaphoreWait;
    }

    private static String causeChain(Throwable failure) {

        StringBuilder classes = new StringBuilder();
        Throwable current = failure;

        for (int depth = 0;
                current != null && depth < 10;
                depth++) {

            if (!classes.isEmpty()) {
                classes.append("->");
            }

            classes.append(
                    current.getClass().getSimpleName());

            Throwable next = current.getCause();

            if (next == current) {
                break;
            }

            current = next;
        }

        return classes.toString();
    }

    private static void stop(ExecutorService worker)
            throws InterruptedException {

        worker.shutdownNow();

        assertTrue(
                worker.awaitTermination(
                        5, TimeUnit.SECONDS));
    }

    private static void releaseMainThreadLock(
            Day17TestDatabase db,
            String key) {

        RLock lock = db.redis.getLock(key);

        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    private static void report(
            String path,
            long started,
            Day17TestDatabase db,
            long slot) {

        System.out.printf(
                "DAY17 PATH %s elapsed_ms=%d " +
                "final_slot=%s reservation_count=%d%n",
                path,
                TimeUnit.NANOSECONDS.toMillis(
                        System.nanoTime() - started),
                db.state(slot),
                db.count(slot));
    }
}
