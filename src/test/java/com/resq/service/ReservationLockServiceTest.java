package com.resq.service;

import com.resq.domain.Reservation;
import com.resq.dto.ReservationCreateDTO;
import com.resq.exception.ReservationBusyException;
import com.resq.exception.ReservationConflictException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Exercises the real lock wrapper with simulated dependency failures, without
 * opening a database or Redis connection. Transaction rollback and real Redis
 * ownership are verified separately by the isolated integration tests.
 */
class ReservationLockServiceTest {

    private static final Long USER_ID = 900_017L;
    private static final Long SLOT_ID = 900_018L;
    private static final String LOCK_KEY = "resq:lock:slot:" + SLOT_ID;

    private RedissonClient redissonClient;
    private RLock lock;
    private ReservationService reservationService;
    private ReservationLockService service;
    private ReservationCreateDTO request;

    @BeforeEach
    void setUp() {
        // Clear any inherited interrupt so this test's assertions are isolated.
        Thread.interrupted();
        redissonClient = mock(RedissonClient.class);
        lock = mock(RLock.class);
        reservationService = mock(ReservationService.class);
        service = new ReservationLockService(redissonClient, reservationService);
        request = new ReservationCreateDTO(SLOT_ID, "day17-unit-request");
        when(redissonClient.getLock(LOCK_KEY)).thenReturn(lock);
    }

    @AfterEach
    void clearInterruptFlag() {
        // InterruptedException is required to restore the flag; never leak it
        // into another JUnit test that reuses this worker thread.
        Thread.interrupted();
    }

    @Test
    void successReturnsDelegateResultAndUnlocksAfterDelegateCompletes() throws InterruptedException {
        acquiredOwnedLock();
        Reservation expected = mock(Reservation.class);
        AtomicBoolean delegateCompleted = new AtomicBoolean(false);
        when(reservationService.create(USER_ID, request)).thenAnswer(invocation -> {
            delegateCompleted.set(true);
            return expected;
        });
        doAnswer(invocation -> {
            assertTrue(delegateCompleted.get(), "Delegate must complete before unlock");
            return null;
        }).when(lock).unlock();

        assertSame(expected, service.create(USER_ID, request));

        InOrder order = inOrder(redissonClient, lock, reservationService);
        order.verify(redissonClient).getLock(LOCK_KEY);
        order.verify(lock).tryLock(2, TimeUnit.SECONDS);
        order.verify(reservationService).create(USER_ID, request);
        order.verify(lock).isHeldByCurrentThread();
        order.verify(lock).unlock();
        order.verifyNoMoreInteractions();
        // Mock ordering alone does not prove a Spring transaction committed;
        // that requires the integration test with a real transaction proxy.
    }

    @Test
    void businessConflictIsPropagatedAndOwnedLockIsReleased() throws InterruptedException {
        acquiredOwnedLock();
        ReservationConflictException failure = new ReservationConflictException("Slot is unavailable");
        when(reservationService.create(USER_ID, request)).thenThrow(failure);

        assertSame(failure, assertThrows(ReservationConflictException.class,
                () -> service.create(USER_ID, request)));

        InOrder order = inOrder(lock, reservationService);
        order.verify(lock).tryLock(2, TimeUnit.SECONDS);
        order.verify(reservationService).create(USER_ID, request);
        order.verify(lock).isHeldByCurrentThread();
        order.verify(lock).unlock();
        order.verifyNoMoreInteractions();
    }

    @Test
    void databaseFailureIsPropagatedAndOwnedLockIsReleased() throws InterruptedException {
        acquiredOwnedLock();
        DataAccessResourceFailureException failure =
                new DataAccessResourceFailureException("Simulated database failure");
        when(reservationService.create(USER_ID, request)).thenThrow(failure);

        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> service.create(USER_ID, request)));

        InOrder order = inOrder(lock, reservationService);
        order.verify(lock).tryLock(2, TimeUnit.SECONDS);
        order.verify(reservationService).create(USER_ID, request);
        order.verify(lock).isHeldByCurrentThread();
        order.verify(lock).unlock();
        order.verifyNoMoreInteractions();
    }

    @Test
    void invalidSlotIsRejectedBeforeAnyLockOrDatabaseCall() {
        assertThrows(IllegalArgumentException.class, () -> service.create(USER_ID, null));
        for (Long invalidSlot : new Long[]{null, 0L, -1L}) {
            ReservationCreateDTO invalid = new ReservationCreateDTO(invalidSlot, "day17-invalid-request");
            assertThrows(IllegalArgumentException.class, () -> service.create(USER_ID, invalid));
        }

        verifyNoInteractions(redissonClient, lock, reservationService);
    }

    @Test
    void acquisitionTimeoutUsesTwoSecondWaitAndRejectsRequestWithoutDelegation()
            throws InterruptedException {
        when(lock.tryLock(2, TimeUnit.SECONDS)).thenReturn(false);

        ReservationBusyException failure = assertThrows(ReservationBusyException.class,
                () -> service.create(USER_ID, request));

        assertEquals(ReservationBusyException.class, failure.getClass());
        assertEquals("预约繁忙，请稍后重试", failure.getMessage());
        verify(redissonClient).getLock(LOCK_KEY);
        verify(lock).tryLock(2, TimeUnit.SECONDS);
        verifyNoMoreInteractions(redissonClient, lock);
        verifyNoInteractions(reservationService);
    }

    @Test
    void interruptedAcquisitionRestoresInterruptAndThrowsReservationBusyException()
            throws InterruptedException {
        InterruptedException interruption = new InterruptedException("Simulated waiting interruption");
        when(lock.tryLock(2, TimeUnit.SECONDS)).thenThrow(interruption);

        ReservationBusyException failure = assertThrows(ReservationBusyException.class,
                () -> service.create(USER_ID, request));

        assertSame(interruption, failure.getCause());
        assertTrue(Thread.currentThread().isInterrupted(), "The caller's interrupt flag must be restored");
        verify(redissonClient).getLock(LOCK_KEY);
        verify(lock).tryLock(2, TimeUnit.SECONDS);
        verifyNoMoreInteractions(redissonClient, lock);
        verifyNoInteractions(reservationService);
    }

    @Test
    void redisFailureWhileGettingLockPropagatesWithoutDelegationOrUnlock() {
        RedisException failure = new RedisException("Simulated Redis getLock failure");
        when(redissonClient.getLock(LOCK_KEY)).thenThrow(failure);

        assertSame(failure, assertThrows(RedisException.class, () -> service.create(USER_ID, request)));

        verify(redissonClient).getLock(LOCK_KEY);
        verifyNoMoreInteractions(redissonClient);
        verifyNoInteractions(lock, reservationService);
    }

    @Test
    void redisFailureWhileAcquiringLockPropagatesWithoutDelegationOrUnlock()
            throws InterruptedException {
        RedisException failure = new RedisException("Simulated Redis tryLock failure");
        when(lock.tryLock(2, TimeUnit.SECONDS)).thenThrow(failure);

        assertSame(failure, assertThrows(RedisException.class, () -> service.create(USER_ID, request)));

        verify(redissonClient).getLock(LOCK_KEY);
        verify(lock).tryLock(2, TimeUnit.SECONDS);
        verifyNoMoreInteractions(redissonClient, lock);
        verifyNoInteractions(reservationService);
    }

    @Test
    void lostOwnershipReturnsDelegateResultWithoutAttemptingUnlock() throws InterruptedException {
        when(lock.tryLock(2, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(false);
        Reservation expected = mock(Reservation.class);
        when(reservationService.create(USER_ID, request)).thenReturn(expected);

        assertSame(expected, service.create(USER_ID, request));

        verify(lock).tryLock(2, TimeUnit.SECONDS);
        verify(lock).isHeldByCurrentThread();
        verify(lock, never()).unlock();
        verify(reservationService).create(USER_ID, request);
        verifyNoMoreInteractions(lock, reservationService);
    }

    @Test
    void lostOwnershipPreservesBusinessFailureWithoutAttemptingUnlock() throws InterruptedException {
        when(lock.tryLock(2, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(false);
        ReservationConflictException failure = new ReservationConflictException("Slot is unavailable");
        when(reservationService.create(USER_ID, request)).thenThrow(failure);

        assertSame(failure, assertThrows(ReservationConflictException.class,
                () -> service.create(USER_ID, request)));

        verify(lock).tryLock(2, TimeUnit.SECONDS);
        verify(lock).isHeldByCurrentThread();
        verify(lock, never()).unlock();
        verifyNoMoreInteractions(lock);
    }

    @Test
    void ownershipCheckRedisFailurePropagatesAndUnlockIsNotAttempted() throws InterruptedException {
        when(lock.tryLock(2, TimeUnit.SECONDS)).thenReturn(true);
        RedisException failure = new RedisException("Simulated Redis ownership check failure");
        when(lock.isHeldByCurrentThread()).thenThrow(failure);
        when(reservationService.create(USER_ID, request)).thenReturn(mock(Reservation.class));

        assertSame(failure, assertThrows(RedisException.class, () -> service.create(USER_ID, request)));

        verify(lock).tryLock(2, TimeUnit.SECONDS);
        verify(lock).isHeldByCurrentThread();
        verify(lock, never()).unlock();
        verifyNoMoreInteractions(lock);
    }

    @Test
    void ownershipCheckFailureCurrentlyMasksBusinessFailure() throws InterruptedException {
        when(lock.tryLock(2, TimeUnit.SECONDS)).thenReturn(true);
        ReservationConflictException businessFailure =
                new ReservationConflictException("Slot is unavailable");
        when(reservationService.create(USER_ID, request)).thenThrow(businessFailure);
        RedisException cleanupFailure = new RedisException("Simulated ownership check failure");
        when(lock.isHeldByCurrentThread()).thenThrow(cleanupFailure);

        RedisException actual = assertThrows(RedisException.class, () -> service.create(USER_ID, request));

        assertSame(cleanupFailure, actual);
        assertEquals(0, actual.getSuppressed().length, "Current finally block does not preserve the original failure");
        verify(lock, never()).unlock();
    }

    @Test
    void unlockRedisFailurePropagatesAfterSuccessfulDelegate() throws InterruptedException {
        acquiredOwnedLock();
        when(reservationService.create(USER_ID, request)).thenReturn(mock(Reservation.class));
        RedisException failure = new RedisException("Simulated Redis unlock failure");
        doThrow(failure).when(lock).unlock();

        assertSame(failure, assertThrows(RedisException.class, () -> service.create(USER_ID, request)));

        verify(reservationService).create(USER_ID, request);
        verify(lock).isHeldByCurrentThread();
        verify(lock).unlock();
    }

    @Test
    void unlockFailureCurrentlyMasksBusinessFailure() throws InterruptedException {
        acquiredOwnedLock();
        ReservationConflictException businessFailure =
                new ReservationConflictException("Slot is unavailable");
        when(reservationService.create(USER_ID, request)).thenThrow(businessFailure);
        RedisException cleanupFailure = new RedisException("Simulated Redis unlock failure");
        doThrow(cleanupFailure).when(lock).unlock();

        RedisException actual = assertThrows(RedisException.class, () -> service.create(USER_ID, request));

        assertSame(cleanupFailure, actual);
        assertEquals(0, actual.getSuppressed().length, "Current finally block does not preserve the original failure");
        verify(lock).isHeldByCurrentThread();
        verify(lock).unlock();
    }

    private void acquiredOwnedLock() throws InterruptedException {
        when(lock.tryLock(2, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
    }
}
