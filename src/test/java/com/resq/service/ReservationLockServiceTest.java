package com.resq.service;

import com.resq.cache.ReservationRequestStateStore;
import com.resq.domain.Reservation;
import com.resq.dto.ReservationCreateDTO;
import com.resq.exception.ReservationBusyException;
import com.resq.exception.ReservationConflictException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisConnectionException;
import org.redisson.client.RedisException;
import org.redisson.client.RedisTimeoutException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.util.UUID;
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
    private static final String REQUEST_ID = "day18-unit-request";
    private static final String LOCK_KEY = "resq:lock:slot:" + SLOT_ID;

    private RedissonClient redissonClient;
    private RLock lock;
    private ReservationService reservationService;
    private ReservationRequestStateStore requestStateStore;
    private ReservationLockService service;
    private ReservationCreateDTO request;

    @BeforeEach
    void setUp() {
        Thread.interrupted();
        redissonClient = mock(RedissonClient.class);
        lock = mock(RLock.class);
        reservationService = mock(ReservationService.class);
        requestStateStore = mock(ReservationRequestStateStore.class);
        service = new ReservationLockService(redissonClient, reservationService, requestStateStore);
        request = new ReservationCreateDTO(SLOT_ID, REQUEST_ID);
        when(redissonClient.getLock(LOCK_KEY)).thenReturn(lock);
        when(requestStateStore.tryStart(eq(REQUEST_ID), eq(USER_ID), eq(SLOT_ID), anyString()))
                .thenReturn(true);
        when(requestStateStore.markSuccess(eq(REQUEST_ID), eq(USER_ID), eq(SLOT_ID), anyString(), anyLong()))
                .thenReturn(true);
    }

    @AfterEach
    void clearInterruptFlag() {
        // InterruptedException must restore the flag without leaking it to the next test.
        Thread.interrupted();
    }

    @Test
    void successReturnsDelegateResultAndUnlocksAfterDelegateCompletes() throws InterruptedException {
        acquiredOwnedLock();
        Reservation expected = reservation();
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

        ArgumentCaptor<String> owner = ArgumentCaptor.forClass(String.class);
        InOrder order = inOrder(reservationService, requestStateStore, redissonClient, lock);
        order.verify(reservationService).findExistingForRetry(USER_ID, request);
        order.verify(requestStateStore).tryStart(eq(REQUEST_ID), eq(USER_ID), eq(SLOT_ID), owner.capture());
        order.verify(redissonClient).getLock(LOCK_KEY);
        order.verify(lock).tryLock(2, TimeUnit.SECONDS);
        order.verify(reservationService).create(USER_ID, request);
        order.verify(lock).isHeldByCurrentThread();
        order.verify(lock).unlock();
        order.verify(requestStateStore).markSuccess(REQUEST_ID, USER_ID, SLOT_ID, owner.getValue(), expected.getId());
        order.verifyNoMoreInteractions();
        assertDoesNotThrow(() -> UUID.fromString(owner.getValue()));
        // Real transaction commit before unlock is verified by the isolated integration test.
    }

    @Test
    void businessConflictIsPropagatedAndOwnedLockIsReleased() throws InterruptedException {
        acquiredOwnedLock();
        ReservationConflictException failure = new ReservationConflictException("Slot is unavailable");
        when(reservationService.create(USER_ID, request)).thenThrow(failure);

        assertSame(failure, assertThrows(ReservationConflictException.class,
                () -> service.create(USER_ID, request)));

        ArgumentCaptor<String> owner = ArgumentCaptor.forClass(String.class);
        InOrder order = inOrder(reservationService, requestStateStore, lock);
        order.verify(reservationService).findExistingForRetry(USER_ID, request);
        order.verify(requestStateStore).tryStart(eq(REQUEST_ID), eq(USER_ID), eq(SLOT_ID), owner.capture());
        order.verify(lock).tryLock(2, TimeUnit.SECONDS);
        order.verify(reservationService).create(USER_ID, request);
        order.verify(lock).isHeldByCurrentThread();
        order.verify(lock).unlock();
        order.verify(requestStateStore).releaseProcessing(REQUEST_ID, USER_ID, SLOT_ID, owner.getValue());
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
        order.verify(reservationService).findExistingForRetry(USER_ID, request);
        order.verify(lock).tryLock(2, TimeUnit.SECONDS);
        order.verify(reservationService).create(USER_ID, request);
        order.verify(lock).isHeldByCurrentThread();
        order.verify(lock).unlock();
        order.verify(reservationService).findExistingForRetry(USER_ID, request);
        order.verifyNoMoreInteractions();
        assertProcessingRetained();
    }

    @Test
    void invalidSlotIsRejectedBeforeAnyLockOrDatabaseCall() {
        assertThrows(IllegalArgumentException.class, () -> service.create(USER_ID, null));
        for (Long invalidSlot : new Long[]{null, 0L, -1L}) {
            ReservationCreateDTO invalid = new ReservationCreateDTO(invalidSlot, REQUEST_ID);
            assertThrows(IllegalArgumentException.class, () -> service.create(USER_ID, invalid));
        }
        for (Long invalidUser : new Long[]{null, 0L, -1L}) {
            assertThrows(IllegalArgumentException.class, () -> service.create(invalidUser, request));
        }
        for (String invalidRequest : new String[]{null, "", " ", "x".repeat(101)}) {
            ReservationCreateDTO invalid = new ReservationCreateDTO(SLOT_ID, invalidRequest);
            assertThrows(IllegalArgumentException.class, () -> service.create(USER_ID, invalid));
        }
        verifyNoInteractions(redissonClient, lock, reservationService, requestStateStore);
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
        verify(reservationService).findExistingForRetry(USER_ID, request);
        verifyNoMoreInteractions(reservationService);
        verifyOwnedProcessingReleased();
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
        verify(reservationService).findExistingForRetry(USER_ID, request);
        verifyNoMoreInteractions(reservationService);
        verifyOwnedProcessingReleased();
    }

    @Test
    void redisFailureWhileGettingLockPropagatesWithoutDelegationOrUnlock() {
        RedisException failure = new RedisException("Simulated Redis getLock failure");
        when(redissonClient.getLock(LOCK_KEY)).thenThrow(failure);

        assertSame(failure, assertThrows(RedisException.class, () -> service.create(USER_ID, request)));

        verify(redissonClient).getLock(LOCK_KEY);
        verifyNoMoreInteractions(redissonClient);
        verifyNoInteractions(lock);
        verify(reservationService, times(2)).findExistingForRetry(USER_ID, request);
        verifyNoMoreInteractions(reservationService);
        assertProcessingRetained();
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
        verify(reservationService, times(2)).findExistingForRetry(USER_ID, request);
        verifyNoMoreInteractions(reservationService);
        assertProcessingRetained();
    }

    @Test
    void lostOwnershipReturnsDelegateResultWithoutAttemptingUnlock() throws InterruptedException {
        when(lock.tryLock(2, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(false);
        Reservation expected = reservation();
        when(reservationService.create(USER_ID, request)).thenReturn(expected);

        assertSame(expected, service.create(USER_ID, request));

        verify(lock).tryLock(2, TimeUnit.SECONDS);
        verify(lock).isHeldByCurrentThread();
        verify(lock, never()).unlock();
        verify(reservationService).findExistingForRetry(USER_ID, request);
        verify(reservationService).create(USER_ID, request);
        verifyNoMoreInteractions(lock, reservationService);
        verifyOwnedSuccess(expected);
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
        verifyOwnedProcessingReleased();
    }

    @Test
    void ownershipCheckRedisFailurePropagatesWhenCommitCannotBeConfirmed() throws InterruptedException {
        when(lock.tryLock(2, TimeUnit.SECONDS)).thenReturn(true);
        RedisException failure = new RedisException("Simulated Redis ownership check failure");
        when(lock.isHeldByCurrentThread()).thenThrow(failure);
        when(reservationService.create(USER_ID, request)).thenReturn(reservation());

        assertSame(failure, assertThrows(RedisException.class, () -> service.create(USER_ID, request)));

        verify(lock).tryLock(2, TimeUnit.SECONDS);
        verify(lock).isHeldByCurrentThread();
        verify(lock, never()).unlock();
        verifyNoMoreInteractions(lock);
        verify(reservationService, times(2)).findExistingForRetry(USER_ID, request);
        assertProcessingRetained();
    }

    @Test
    void ownershipCheckFailureCurrentlyMasksBusinessFailureWhenNothingWasCommitted() throws InterruptedException {
        when(lock.tryLock(2, TimeUnit.SECONDS)).thenReturn(true);
        ReservationConflictException businessFailure = new ReservationConflictException("Slot is unavailable");
        when(reservationService.create(USER_ID, request)).thenThrow(businessFailure);
        RedisException cleanupFailure = new RedisException("Simulated ownership check failure");
        when(lock.isHeldByCurrentThread()).thenThrow(cleanupFailure);

        RedisException actual = assertThrows(RedisException.class, () -> service.create(USER_ID, request));

        // Retain the Day17 regression observation; this is an existing production limitation.
        assertSame(cleanupFailure, actual);
        assertEquals(0, actual.getSuppressed().length);
        verify(lock, never()).unlock();
        verify(reservationService, times(2)).findExistingForRetry(USER_ID, request);
        assertProcessingRetained();
    }

    @Test
    void unlockRedisFailurePropagatesWhenCommitCannotBeConfirmed() throws InterruptedException {
        acquiredOwnedLock();
        when(reservationService.create(USER_ID, request)).thenReturn(reservation());
        RedisException failure = new RedisException("Simulated Redis unlock failure");
        doThrow(failure).when(lock).unlock();

        assertSame(failure, assertThrows(RedisException.class, () -> service.create(USER_ID, request)));

        verify(reservationService).create(USER_ID, request);
        verify(lock).isHeldByCurrentThread();
        verify(lock).unlock();
        verify(reservationService, times(2)).findExistingForRetry(USER_ID, request);
        assertProcessingRetained();
    }

    @Test
    void unlockFailureCurrentlyMasksBusinessFailureWhenNothingWasCommitted() throws InterruptedException {
        acquiredOwnedLock();
        ReservationConflictException businessFailure = new ReservationConflictException("Slot is unavailable");
        when(reservationService.create(USER_ID, request)).thenThrow(businessFailure);
        RedisException cleanupFailure = new RedisException("Simulated Redis unlock failure");
        doThrow(cleanupFailure).when(lock).unlock();

        RedisException actual = assertThrows(RedisException.class, () -> service.create(USER_ID, request));

        // Retain the Day17 regression observation; this is an existing production limitation.
        assertSame(cleanupFailure, actual);
        assertEquals(0, actual.getSuppressed().length);
        verify(lock).isHeldByCurrentThread();
        verify(lock).unlock();
        verify(reservationService, times(2)).findExistingForRetry(USER_ID, request);
        assertProcessingRetained();
    }

    @Test
    void completedRequestIsReturnedFromMysqlBeforeAnyRedisInteraction() {
        Reservation existing = reservation();
        when(reservationService.findExistingForRetry(USER_ID, request)).thenReturn(existing);

        assertSame(existing, service.create(USER_ID, request));

        verify(reservationService).findExistingForRetry(USER_ID, request);
        verifyNoMoreInteractions(reservationService);
        verifyNoInteractions(redissonClient, lock, requestStateStore);
    }

    @Test
    void repeatedProcessingRequestIsBusyWithoutCreatingAgain() {
        when(requestStateStore.tryStart(eq(REQUEST_ID), eq(USER_ID), eq(SLOT_ID), anyString()))
                .thenReturn(false);
        when(requestStateStore.getState(REQUEST_ID))
                .thenReturn("PROCESSING|other-owner|" + USER_ID + "|" + SLOT_ID);

        assertThrows(ReservationBusyException.class, () -> service.create(USER_ID, request));

        verify(requestStateStore).getState(REQUEST_ID);
        verify(reservationService, times(2)).findExistingForRetry(USER_ID, request);
        verifyNoMoreInteractions(reservationService);
        verifyNoInteractions(redissonClient, lock);
        assertProcessingRetained();
    }

    @Test
    void repeatedRequestWithDifferentBusinessParametersIsConflict() {
        when(requestStateStore.tryStart(eq(REQUEST_ID), eq(USER_ID), eq(SLOT_ID), anyString()))
                .thenReturn(false);
        when(requestStateStore.getState(REQUEST_ID)).thenReturn("SUCCESS|55|42|" + SLOT_ID);

        ReservationConflictException failure = assertThrows(ReservationConflictException.class,
                () -> service.create(USER_ID, request));

        assertEquals("requestId 已用于其他预约操作", failure.getMessage());
        verify(reservationService, times(2)).findExistingForRetry(USER_ID, request);
        verifyNoMoreInteractions(reservationService);
        verifyNoInteractions(redissonClient, lock);
        assertProcessingRetained();
    }

    @Test
    void repeatedRequestRechecksMysqlToFindAConcurrentCommit() {
        Reservation existing = reservation();
        when(requestStateStore.tryStart(eq(REQUEST_ID), eq(USER_ID), eq(SLOT_ID), anyString()))
                .thenReturn(false);
        when(reservationService.findExistingForRetry(USER_ID, request)).thenReturn(null, existing);

        assertSame(existing, service.create(USER_ID, request));

        verify(reservationService, times(2)).findExistingForRetry(USER_ID, request);
        verifyNoMoreInteractions(reservationService);
        verifyNoInteractions(redissonClient, lock);
        assertProcessingRetained();
    }

    @Test
    void unavailableRequestStateRegistrationFallsBackToMysqlWithoutRedisson() {
        when(requestStateStore.tryStart(eq(REQUEST_ID), eq(USER_ID), eq(SLOT_ID), anyString()))
                .thenThrow(new RedisConnectionFailureException("Simulated state registration outage"));
        Reservation expected = reservation();
        when(reservationService.create(USER_ID, request)).thenReturn(expected);

        assertSame(expected, service.create(USER_ID, request));

        verify(reservationService).create(USER_ID, request);
        verifyNoInteractions(redissonClient, lock);
        assertProcessingRetained();
    }

    @Test
    void unavailableRequestStateReadFallsBackToMysqlWithoutRedisson() {
        when(requestStateStore.tryStart(eq(REQUEST_ID), eq(USER_ID), eq(SLOT_ID), anyString()))
                .thenReturn(false);
        when(requestStateStore.getState(REQUEST_ID))
                .thenThrow(new RedisConnectionFailureException("Simulated state read outage"));
        Reservation expected = reservation();
        when(reservationService.create(USER_ID, request)).thenReturn(expected);

        assertSame(expected, service.create(USER_ID, request));

        verify(reservationService).create(USER_ID, request);
        verifyNoInteractions(redissonClient, lock);
        assertProcessingRetained();
    }

    @Test
    void redissonConnectionFailureFallsBackToMysqlAndMarksOwnedSuccess() {
        when(redissonClient.getLock(LOCK_KEY))
                .thenThrow(new RedisConnectionException("Simulated Redisson connection outage"));
        Reservation expected = reservation();
        when(reservationService.create(USER_ID, request)).thenReturn(expected);

        assertSame(expected, service.create(USER_ID, request));

        verify(reservationService).create(USER_ID, request);
        verifyNoInteractions(lock);
        verifyOwnedSuccess(expected);
    }

    @Test
    void redissonTimeoutFallsBackToMysqlWithoutUnlockingAnUnacquiredLock() throws InterruptedException {
        when(lock.tryLock(2, TimeUnit.SECONDS))
                .thenThrow(new RedisTimeoutException("Simulated Redisson timeout"));
        Reservation expected = reservation();
        when(reservationService.create(USER_ID, request)).thenReturn(expected);

        assertSame(expected, service.create(USER_ID, request));

        verify(reservationService).create(USER_ID, request);
        verify(lock).tryLock(2, TimeUnit.SECONDS);
        verifyNoMoreInteractions(lock);
        verifyOwnedSuccess(expected);
    }

    @Test
    void duplicateKeyIsResolvedAfterDelegateRollbackAndBeforeUnlock() throws InterruptedException {
        acquiredOwnedLock();
        Reservation existing = reservation();
        when(reservationService.create(USER_ID, request)).thenThrow(new DuplicateKeyException("retry race"));
        when(reservationService.findExistingForRetry(USER_ID, request)).thenReturn(null, existing);

        assertSame(existing, service.create(USER_ID, request));

        InOrder order = inOrder(reservationService, lock);
        order.verify(reservationService).findExistingForRetry(USER_ID, request);
        order.verify(lock).tryLock(2, TimeUnit.SECONDS);
        order.verify(reservationService).create(USER_ID, request);
        order.verify(reservationService).findExistingForRetry(USER_ID, request);
        order.verify(lock).isHeldByCurrentThread();
        order.verify(lock).unlock();
        order.verifyNoMoreInteractions();
        verifyOwnedSuccess(existing);
    }

    @Test
    void ownershipCheckFailureAfterCommitCanBeRecoveredFromMysql() throws InterruptedException {
        when(lock.tryLock(2, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenThrow(new RedisException("ownership check failed"));
        Reservation committed = reservation();
        when(reservationService.create(USER_ID, request)).thenReturn(committed);
        when(reservationService.findExistingForRetry(USER_ID, request)).thenReturn(null, committed);

        assertSame(committed, service.create(USER_ID, request));

        verify(reservationService, times(2)).findExistingForRetry(USER_ID, request);
        verify(lock, never()).unlock();
        verifyOwnedSuccess(committed);
    }

    @Test
    void unlockFailureAfterCommitCanBeRecoveredFromMysql() throws InterruptedException {
        acquiredOwnedLock();
        doThrow(new RedisException("unlock failed")).when(lock).unlock();
        Reservation committed = reservation();
        when(reservationService.create(USER_ID, request)).thenReturn(committed);
        when(reservationService.findExistingForRetry(USER_ID, request)).thenReturn(null, committed);

        assertSame(committed, service.create(USER_ID, request));

        verify(reservationService).create(USER_ID, request);
        verify(reservationService, times(2)).findExistingForRetry(USER_ID, request);
        verify(lock).unlock();
        verifyOwnedSuccess(committed);
    }

    @Test
    void unknownFailureAndFailedMysqlRecoveryPreserveOriginalException() throws InterruptedException {
        acquiredOwnedLock();
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("uncertain result");
        DataAccessResourceFailureException lookupFailure = new DataAccessResourceFailureException("recovery unavailable");
        when(reservationService.create(USER_ID, request)).thenThrow(failure);
        when(reservationService.findExistingForRetry(USER_ID, request)).thenReturn(null).thenThrow(lookupFailure);

        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> service.create(USER_ID, request)));

        assertArrayEquals(new Throwable[]{lookupFailure}, failure.getSuppressed());
        verify(lock).unlock();
        assertProcessingRetained();
    }

    @Test
    void mysqlFallbackRecoversAnUncertainCreateFromMysql() {
        when(requestStateStore.tryStart(eq(REQUEST_ID), eq(USER_ID), eq(SLOT_ID), anyString()))
                .thenThrow(new RedisConnectionFailureException("state unavailable"));
        Reservation committed = reservation();
        when(reservationService.create(USER_ID, request))
                .thenThrow(new DataAccessResourceFailureException("response lost after commit"));
        when(reservationService.findExistingForRetry(USER_ID, request)).thenReturn(null, committed);

        assertSame(committed, service.create(USER_ID, request));

        verify(reservationService).create(USER_ID, request);
        verify(reservationService, times(2)).findExistingForRetry(USER_ID, request);
        verifyNoInteractions(redissonClient, lock);
        assertProcessingRetained();
    }

    @Test
    void successStateWriteFailureCannotTurnCommittedReservationIntoFailure() throws InterruptedException {
        acquiredOwnedLock();
        Reservation committed = reservation();
        when(reservationService.create(USER_ID, request)).thenReturn(committed);
        when(requestStateStore.markSuccess(eq(REQUEST_ID), eq(USER_ID), eq(SLOT_ID), anyString(), eq(committed.getId())))
                .thenThrow(new RedisConnectionFailureException("success state unavailable"));

        assertSame(committed, service.create(USER_ID, request));

        verify(lock).unlock();
        verifyOwnedSuccess(committed);
    }

    @Test
    void processingCleanupFailureCannotMaskBusinessConflict() throws InterruptedException {
        acquiredOwnedLock();
        ReservationConflictException conflict = new ReservationConflictException("Slot is unavailable");
        when(reservationService.create(USER_ID, request)).thenThrow(conflict);
        when(requestStateStore.releaseProcessing(eq(REQUEST_ID), eq(USER_ID), eq(SLOT_ID), anyString()))
                .thenThrow(new RedisConnectionFailureException("processing cleanup unavailable"));

        assertSame(conflict, assertThrows(ReservationConflictException.class,
                () -> service.create(USER_ID, request)));

        verify(lock).unlock();
        verifyOwnedProcessingReleased();
    }

    private Reservation reservation() {
        Reservation result = new Reservation();
        result.setId(900_019L);
        result.setUserId(USER_ID);
        result.setSlotId(SLOT_ID);
        result.setRequestId(REQUEST_ID);
        return result;
    }

    private void acquiredOwnedLock() throws InterruptedException {
        when(lock.tryLock(2, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
    }

    private void verifyOwnedSuccess(Reservation expected) {
        ArgumentCaptor<String> owner = ArgumentCaptor.forClass(String.class);
        verify(requestStateStore).tryStart(eq(REQUEST_ID), eq(USER_ID), eq(SLOT_ID), owner.capture());
        verify(requestStateStore).markSuccess(REQUEST_ID, USER_ID, SLOT_ID, owner.getValue(), expected.getId());
        verify(requestStateStore, never()).releaseProcessing(anyString(), anyLong(), anyLong(), anyString());
    }

    private void verifyOwnedProcessingReleased() {
        ArgumentCaptor<String> owner = ArgumentCaptor.forClass(String.class);
        verify(requestStateStore).tryStart(eq(REQUEST_ID), eq(USER_ID), eq(SLOT_ID), owner.capture());
        verify(requestStateStore).releaseProcessing(REQUEST_ID, USER_ID, SLOT_ID, owner.getValue());
        verify(requestStateStore, never()).markSuccess(anyString(), anyLong(), anyLong(), anyString(), anyLong());
    }

    private void assertProcessingRetained() {
        verify(requestStateStore, never()).releaseProcessing(anyString(), anyLong(), anyLong(), anyString());
        verify(requestStateStore, never()).markSuccess(anyString(), anyLong(), anyLong(), anyString(), anyLong());
    }
}
