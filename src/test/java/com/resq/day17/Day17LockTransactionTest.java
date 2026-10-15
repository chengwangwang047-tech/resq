package com.resq.day17;

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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Connection;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real MySQL transactions and Redisson locks on the guarded private endpoints only. */
@EnabledIfSystemProperty(named = "resq.day17.integration", matches = "true")
class Day17LockTransactionTest {

    @Test
    void successCommitsBeforeRealUnlockAndAnotherThreadCanAcquire() throws Exception {
        try (Day17TestDatabase db = new Day17TestDatabase()) {
            var f = db.fixture();
            String key = lockKey(f.slot());
            long started = System.nanoTime();
            try (Connection observer = db.pool.getConnection()) {
                // Hold this connection before the service starts so the observer
                // cannot reuse the service transaction's physical connection.
                assertTrue(observer.getAutoCommit());
                JdbcTemplate external = new JdbcTemplate(new SingleConnectionDataSource(observer, true));
                RLock observedLock = spy(db.redis.getLock(key));
                AtomicBoolean observedCommittedRow = new AtomicBoolean();
                doAnswer(invocation -> {
                    assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                            "Transaction proxy must finish before lock cleanup");
                    assertEquals("RESERVED", external.queryForObject(
                            "SELECT status FROM resource_slot WHERE id=?", String.class, f.slot()));
                    assertEquals(1, external.queryForObject(
                            "SELECT COUNT(*) FROM reservation WHERE slot_id=? AND status='PENDING'",
                            Integer.class, f.slot()));
                    observedCommittedRow.set(true);
                    return invocation.callRealMethod();
                }).when(observedLock).unlock();
                ReservationLockService service = withObservedLock(db.service, key, observedLock);

                Reservation created = service.create(f.user(), request(f.slot()));

                assertNotNull(created.getId());
                assertTrue(observedCommittedRow.get());
                verify(observedLock).tryLock(2, TimeUnit.SECONDS);
                verify(observedLock).unlock();
                assertEquals(1, db.count(f.slot()));
                assertReleasedForAnotherThread(db, key);
                report("success_commit_before_unlock", started, db, f.slot());
            } finally {
                releaseMainThreadLock(db, key);
                db.cleanup(f);
            }
        }
    }

    @Test
    void actualBusinessConflictPreservesExistingReservationAndReleasesLock() throws Exception {
        try (Day17TestDatabase db = new Day17TestDatabase()) {
            var f = db.fixture();
            String key = lockKey(f.slot());
            long started = System.nanoTime();
            try {
                ReservationLockService service = new ReservationLockService(db.redis, db.service);
                service.create(f.user(), request(f.slot()));

                assertThrows(ReservationConflictException.class,
                        () -> service.create(f.user(), request(f.slot())));

                assertEquals("RESERVED", db.state(f.slot()));
                assertEquals(1, db.count(f.slot()));
                assertReleasedForAnotherThread(db, key);
                report("business_conflict_release", started, db, f.slot());
            } finally {
                releaseMainThreadLock(db, key);
                db.cleanup(f);
            }
        }
    }

    @Test
    void realDuplicateRequestInsertRollsBackSlotUpdateAndReleasesLock() throws Exception {
        try (Day17TestDatabase db = new Day17TestDatabase()) {
            var first = db.fixture();
            var second = db.fixture();
            String firstKey = lockKey(first.slot());
            String secondKey = lockKey(second.slot());
            long started = System.nanoTime();
            try {
                ReservationLockService service = new ReservationLockService(db.redis, db.service);
                String duplicateRequest = "day17-duplicate-" + UUID.randomUUID();
                service.create(first.user(), new ReservationCreateDTO(first.slot(), duplicateRequest));

                // Same fake user and request, different fake slot: conditional
                // slot UPDATE succeeds before the real unique index rejects INSERT.
                assertThrows(DataIntegrityViolationException.class,
                        () -> service.create(first.user(),
                                new ReservationCreateDTO(second.slot(), duplicateRequest)));

                assertEquals("RESERVED", db.state(first.slot()));
                assertEquals(1, db.count(first.slot()));
                assertEquals("AVAILABLE", db.state(second.slot()), "Failed INSERT must roll back UPDATE");
                assertEquals(0, db.count(second.slot()));
                assertReleasedForAnotherThread(db, secondKey);
                System.out.println("DAY17 PATH real_duplicate_insert rollback_state=AVAILABLE reservation_count=0 released=true");

                Reservation retry = service.create(first.user(), request(second.slot()));
                assertNotNull(retry.getId());
                assertEquals("RESERVED", db.state(second.slot()));
                assertEquals(1, db.count(second.slot()));
                assertReleasedForAnotherThread(db, secondKey);
                report("database_rollback_then_retry", started, db, second.slot());
            } finally {
                releaseMainThreadLock(db, firstKey);
                releaseMainThreadLock(db, secondKey);
                db.cleanup(second);
                db.cleanup(first);
            }
        }
    }

    @Test
    void simulatedDatabaseTransportFailureAfterRealUpdateRollsBackAndReleasesLock() throws Exception {
        try (Day17TestDatabase db = new Day17TestDatabase()) {
            var f = db.fixture();
            String key = lockKey(f.slot());
            long started = System.nanoTime();
            try {
                ReservationMapper failingMapper = spy(db.mapper);
                DataAccessResourceFailureException failure =
                        new DataAccessResourceFailureException("Day17 simulated database transport failure");
                AtomicBoolean realUpdateReached = new AtomicBoolean();
                doAnswer(invocation -> {
                    assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                    assertEquals("RESERVED", db.state(f.slot()), "Real UPDATE must precede injected failure");
                    realUpdateReached.set(true);
                    throw failure;
                }).when(failingMapper).insert(any(Reservation.class));
                ReservationService transactional = db.transactional(
                        new ReservationService(failingMapper, db.invalidator));
                ReservationLockService service = new ReservationLockService(db.redis, transactional);

                assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                        () -> service.create(f.user(), request(f.slot()))));

                assertTrue(realUpdateReached.get());
                assertEquals("AVAILABLE", db.state(f.slot()));
                assertEquals(0, db.count(f.slot()));
                verify(db.cache, never()).evict(anyLong(), any(LocalDate.class));
                assertReleasedForAnotherThread(db, key);
                report("injected_database_failure_rollback", started, db, f.slot());
            } finally {
                releaseMainThreadLock(db, key);
                db.cleanup(f);
            }
        }
    }

    @Test
    void realAcquisitionWaitTimesOutNearTwoSecondsAndKeepsOwnersLock() throws Exception {
        try (Day17TestDatabase db = new Day17TestDatabase()) {
            var f = db.fixture();
            String key = lockKey(f.slot());
            RLock owner = db.redis.getLock(key);
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                owner.lock();
                RLock contender = spy(db.redis.getLock(key));
                ReservationLockService service = withObservedLock(db.service, key, contender);
                Future<Outcome> future = worker.submit(() -> capture(service, f.user(), request(f.slot())));

                Outcome result = future.get(8, TimeUnit.SECONDS);

                assertNotNull(result.failure());
                assertEquals(ReservationBusyException.class, result.failure().getClass());
                assertTrue(result.elapsedMillis() >= 1700 && result.elapsedMillis() <= 6000,
                        "Expected a real 2-second lock wait; actual ms=" + result.elapsedMillis());
                assertFalse(result.interrupted());
                assertTrue(owner.isHeldByCurrentThread(), "Contender must not release the owner's lock");
                verify(contender).tryLock(2, TimeUnit.SECONDS);
                verify(contender, never()).unlock();
                assertEquals("AVAILABLE", db.state(f.slot()));
                assertEquals(0, db.count(f.slot()));
                System.out.printf("DAY17 PATH actual_lock_timeout elapsed_ms=%d owner_preserved=true reservation_count=0%n",
                        result.elapsedMillis());
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
    void realWaitingThreadInterruptionRestoresFlagWithoutReleasingOwnersLock() throws Exception {
        try (Day17TestDatabase db = new Day17TestDatabase()) {
            var f = db.fixture();
            String key = lockKey(f.slot());
            RLock owner = db.redis.getLock(key);
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                owner.lock();
                RLock contender = spy(db.redis.getLock(key));
                CountDownLatch enteredWait = new CountDownLatch(1);
                doAnswer(invocation -> {
                    enteredWait.countDown();
                    return invocation.callRealMethod();
                }).when(contender).tryLock(2, TimeUnit.SECONDS);
                ReservationLockService service = withObservedLock(db.service, key, contender);
                AtomicReference<Thread> waitingThread = new AtomicReference<>();
                Future<Outcome> future = worker.submit(() -> {
                    waitingThread.set(Thread.currentThread());
                    return capture(service, f.user(), request(f.slot()));
                });
                assertTrue(enteredWait.await(5, TimeUnit.SECONDS), "Worker must enter actual tryLock");
                // Redisson first waits on Redis command/subscription promises,
                // which can wrap an early interrupt as RedisException. Interrupt
                // only once the actual semaphore lock-wait phase is observable.
                await().pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(5))
                        .atMost(Duration.ofMillis(1500))
                        .until(() -> isRedissonSemaphoreWait(waitingThread.get()));

                waitingThread.get().interrupt();
                Outcome result = future.get(5, TimeUnit.SECONDS);

                assertInstanceOf(ReservationBusyException.class, result.failure());
                assertInstanceOf(InterruptedException.class, result.failure().getCause());
                assertTrue(result.interrupted(), "Actual worker interrupt flag must be restored");
                assertTrue(owner.isHeldByCurrentThread());
                verify(contender).tryLock(2, TimeUnit.SECONDS);
                verify(contender, never()).unlock();
                assertEquals("AVAILABLE", db.state(f.slot()));
                assertEquals(0, db.count(f.slot()));
                System.out.printf("DAY17 PATH actual_lock_interruption elapsed_ms=%d interrupt_restored=true owner_preserved=true%n",
                        result.elapsedMillis());
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
    void actualPreInterruptedRedisCommandReportsLibraryFailureAndPreservesOwnersLock() throws Exception {
        try (Day17TestDatabase db = new Day17TestDatabase()) {
            var f = db.fixture();
            String key = lockKey(f.slot());
            RLock owner = db.redis.getLock(key);
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                owner.lock();
                RLock contender = spy(db.redis.getLock(key));
                doAnswer(invocation -> {
                    Thread.currentThread().interrupt();
                    return invocation.callRealMethod();
                }).when(contender).tryLock(2, TimeUnit.SECONDS);
                ReservationLockService service = withObservedLock(db.service, key, contender);

                Outcome result = worker.submit(() -> capture(service, f.user(), request(f.slot())))
                        .get(5, TimeUnit.SECONDS);

                assertInstanceOf(RedisException.class, result.failure());
                assertTrue(owner.isHeldByCurrentThread());
                verify(contender).tryLock(2, TimeUnit.SECONDS);
                verify(contender, never()).unlock();
                assertEquals("AVAILABLE", db.state(f.slot()));
                assertEquals(0, db.count(f.slot()));
                // The lock wrapper restores the flag only for InterruptedException.
                // Preserve evidence of this dependency's actual wrapped exception
                // and flag rather than assuming the semaphore-path result applies.
                System.out.printf("DAY17 PATH actual_preinterrupted_redis_command elapsed_ms=%d cause_chain=%s interrupt_flag=%s owner_preserved=true reservation_count=0%n",
                        result.elapsedMillis(), causeChain(result.failure()), result.interrupted());
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
    void cacheEvictionFailureAfterCommitKeepsReservationAndRetriesAfterLockRelease() throws Exception {
        try (Day17TestDatabase db = new Day17TestDatabase()) {
            var f = db.fixture();
            String key = lockKey(f.slot());
            long started = System.nanoTime();
            try {
                when(db.cache.evict(anyLong(), any(LocalDate.class))).thenReturn(false, true);
                ReservationLockService service = new ReservationLockService(db.redis, db.service);

                Reservation created = service.create(f.user(), request(f.slot()));

                assertNotNull(created.getId());
                assertEquals("RESERVED", db.state(f.slot()));
                assertEquals(1, db.count(f.slot()));
                verify(db.cache, times(1)).evict(f.resource(), Day17TestDatabase.START.toLocalDate());
                assertReleasedForAnotherThread(db, key);

                db.invalidator.retryFailedEvictions();
                verify(db.cache, times(2)).evict(f.resource(), Day17TestDatabase.START.toLocalDate());
                db.invalidator.retryFailedEvictions();
                verify(db.cache, times(2)).evict(f.resource(), Day17TestDatabase.START.toLocalDate());
                assertEquals(1, db.count(f.slot()));
                report("cache_failure_after_commit_then_retry", started, db, f.slot());
            } finally {
                releaseMainThreadLock(db, key);
                db.cleanup(f);
            }
        }
    }

    @Test
    void actualClosedRedisClientCleanupFailureKeepsCommitAndLockEventuallyExpires() throws Exception {
        try (Day17TestDatabase db = new Day17TestDatabase()) {
            var f = db.fixture();
            String key = lockKey(f.slot());
            RedissonClient isolatedClient = null;
            long started = System.nanoTime();
            try {
                // Only this expendable client uses a shorter watchdog. Neither
                // the application's settings nor db.redis are changed/shut down.
                Config config = new Config();
                config.setThreads(4);
                config.setNettyThreads(4);
                config.setLockWatchdogTimeout(2000);
                config.useSingleServer().setAddress("redis://127.0.0.1:16317")
                        .setConnectionPoolSize(4).setConnectionMinimumIdleSize(1)
                        .setSubscriptionConnectionPoolSize(2).setSubscriptionConnectionMinimumIdleSize(1)
                        .setTimeout(1500);
                isolatedClient = Redisson.create(config);
                RedissonClient disposable = isolatedClient;
                ReservationCreateDTO dto = request(f.slot());
                ReservationService delegate = mock(ReservationService.class);
                when(delegate.create(f.user(), dto)).thenAnswer(invocation -> {
                    Reservation committed = db.service.create(f.user(), dto);
                    // Close real client connections after the actual MySQL
                    // transaction returns but before the wrapper's finally.
                    disposable.shutdown(0, 100, TimeUnit.MILLISECONDS);
                    return committed;
                });
                ReservationLockService service = new ReservationLockService(disposable, delegate);

                RuntimeException failure = assertThrows(RuntimeException.class,
                        () -> service.create(f.user(), dto));

                assertTrue(failure.getClass().getSimpleName().contains("Shutdown"),
                        "Expected actual Redisson shutdown failure, got " + failure.getClass().getName());
                verify(delegate).create(f.user(), dto);
                assertEquals("RESERVED", db.state(f.slot()));
                assertEquals(1, db.count(f.slot()));
                RLock observer = db.redis.getLock(key);
                assertTrue(observer.isLocked(), "Failed cleanup must leave the real lock until TTL expiry");
                long remainingTtl = observer.remainTimeToLive();
                assertTrue(remainingTtl > 0 && remainingTtl <= 2000,
                        "Expected shortened private-client watchdog TTL, got " + remainingTtl);
                long expiryStarted = System.nanoTime();

                await().pollInterval(Duration.ofMillis(50)).atMost(Duration.ofSeconds(6))
                        .until(() -> !observer.isLocked());

                long expiryMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - expiryStarted);
                assertReleasedForAnotherThread(db, key);
                assertEquals(1, db.count(f.slot()));
                System.out.printf("DAY17 PATH actual_closed_redis_client exception=%s elapsed_ms=%d ttl_at_failure_ms=%d ttl_release_ms=%d committed=true release_reacquired=true test_watchdog_ms=2000%n",
                        failure.getClass().getSimpleName(),
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), remainingTtl, expiryMillis);
                // This is a real client shutdown failure, not a network partition,
                // Redis server outage, or proof about the production watchdog TTL.
            } finally {
                if (isolatedClient != null && !isolatedClient.isShutdown()) {
                    isolatedClient.shutdown(0, 100, TimeUnit.MILLISECONDS);
                }
                await().pollInterval(Duration.ofMillis(50)).atMost(Duration.ofSeconds(6))
                        .until(() -> !db.redis.getLock(key).isLocked());
                db.cleanup(f);
            }
        }
    }

    private static ReservationLockService withObservedLock(ReservationService transactional,
                                                           String key, RLock observed) {
        RedissonClient client = mock(RedissonClient.class);
        when(client.getLock(key)).thenReturn(observed);
        return new ReservationLockService(client, transactional);
    }

    private static ReservationCreateDTO request(long slot) {
        return new ReservationCreateDTO(slot, "day17-integration-" + UUID.randomUUID());
    }

    private static String lockKey(long slot) {
        return "resq:lock:slot:" + slot;
    }

    private static void assertReleasedForAnotherThread(Day17TestDatabase db, String key) throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> acquired = worker.submit(() -> {
                RLock lock = db.redis.getLock(key);
                boolean owned = lock.tryLock(1, TimeUnit.SECONDS);
                try {
                    return owned && lock.isHeldByCurrentThread();
                } finally {
                    if (owned && lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                }
            });
            assertTrue(acquired.get(5, TimeUnit.SECONDS), "A different thread must acquire the released lock");
        } finally {
            stop(worker);
        }
    }

    private static Outcome capture(ReservationLockService service, long user, ReservationCreateDTO dto) {
        long started = System.nanoTime();
        Throwable failure = null;
        try {
            service.create(user, dto);
        } catch (Throwable caught) {
            failure = caught;
        }
        boolean interrupted = Thread.currentThread().isInterrupted();
        Thread.interrupted(); // Record then clear the worker's flag before executor reuse/cleanup.
        return new Outcome(failure, interrupted, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }

    private record Outcome(Throwable failure, boolean interrupted, long elapsedMillis) {}

    private static boolean isRedissonSemaphoreWait(Thread thread) {
        if (thread == null) {
            return false;
        }
        boolean insideRedissonLock = false;
        boolean semaphoreWait = false;
        for (StackTraceElement frame : thread.getStackTrace()) {
            if (frame.getClassName().equals("org.redisson.RedissonLock")
                    && frame.getMethodName().equals("tryLock")) {
                insideRedissonLock = true;
            }
            if ((frame.getClassName().equals("java.util.concurrent.Semaphore")
                    && frame.getMethodName().equals("tryAcquire"))
                    || (frame.getClassName().equals("java.util.concurrent.locks.AbstractQueuedSynchronizer")
                    && frame.getMethodName().equals("tryAcquireSharedNanos"))) {
                semaphoreWait = true;
            }
        }
        return insideRedissonLock && semaphoreWait;
    }

    private static String causeChain(Throwable failure) {
        StringBuilder classes = new StringBuilder();
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 10; depth++) {
            if (!classes.isEmpty()) {
                classes.append("->");
            }
            classes.append(current.getClass().getSimpleName());
            Throwable next = current.getCause();
            if (next == current) {
                break;
            }
            current = next;
        }
        return classes.toString();
    }

    private static void stop(ExecutorService worker) throws InterruptedException {
        worker.shutdownNow();
        assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS), "Test worker did not terminate within its bound");
    }

    private static void releaseMainThreadLock(Day17TestDatabase db, String key) {
        RLock lock = db.redis.getLock(key);
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    private static void report(String path, long started, Day17TestDatabase db, long slot) {
        System.out.printf("DAY17 PATH %s elapsed_ms=%d final_slot=%s reservation_count=%d%n",
                path, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), db.state(slot), db.count(slot));
    }
}
