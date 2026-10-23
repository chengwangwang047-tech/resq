
package com.resq.service;

import com.resq.cache.ReservationRequestStateStore;
import com.resq.domain.Reservation;
import com.resq.dto.ReservationCreateDTO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisTimeoutException;

import org.springframework.data.redis.RedisConnectionFailureException;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReservationLockServiceFallbackTest {

    private RedissonClient redissonClient;
    private ReservationService reservationService;
    private ReservationRequestStateStore stateStore;

    private ReservationLockService lockService;

    private static final Long USER_ID = 23L;
    private static final Long SLOT_ID = 107L;

    @BeforeEach
    void setUp() {

        // 创建三个模拟依赖
        redissonClient = mock(RedissonClient.class);
        reservationService = mock(ReservationService.class);
        stateStore = mock(ReservationRequestStateStore.class);

        // 创建真正的被测试对象
        lockService = new ReservationLockService(
                redissonClient,
                reservationService,
                stateStore, mock(ReservationRateLimiter.class));
    }

    private ReservationCreateDTO newRequest() {
        return new ReservationCreateDTO(
                SLOT_ID,
                "day18-fallback-" + UUID.randomUUID());
    }

    /**
     * 场景 1：
     * Redis tryStart() 连接失败
     * 应跳过 Redis，直接使用 MySQL Service。
     */
    @Test
    void redisTryStartFailureFallsBackToMysql() {

        ReservationCreateDTO dto = newRequest();
        Reservation expected = mock(Reservation.class);

        when(stateStore.tryStart(
                eq(dto.getRequestId()),
                eq(USER_ID),
                eq(SLOT_ID),
                anyString()))
                .thenThrow(new RedisConnectionFailureException(
                        "模拟 Redis 连接失败"));

        when(reservationService.create(USER_ID, dto))
                .thenReturn(expected);

        Reservation actual = lockService.create(USER_ID, dto);

        assertSame(expected, actual);

        verify(reservationService).create(USER_ID, dto);

        // 整条降级路径不应再访问 Redisson
        verifyNoInteractions(redissonClient);
    }

    /**
     * 场景 2：
     * Redis tryStart() 返回 false，
     * 但随后 getState() 连接失败。
     */
    @Test
    void redisGetStateFailureFallsBackToMysql() {

        ReservationCreateDTO dto = newRequest();
        Reservation expected = mock(Reservation.class);

        when(stateStore.tryStart(
                eq(dto.getRequestId()),
                eq(USER_ID),
                eq(SLOT_ID),
                anyString()))
                .thenReturn(false);

        when(stateStore.getState(dto.getRequestId()))
                .thenThrow(new RedisConnectionFailureException(
                        "模拟 Redis 状态读取失败"));

        when(reservationService.create(USER_ID, dto))
                .thenReturn(expected);

        Reservation actual = lockService.create(USER_ID, dto);

        assertSame(expected, actual);

        verify(reservationService).create(USER_ID, dto);

        verifyNoInteractions(redissonClient);
    }

    /**
     * 场景 3：
     * Redis 幂等状态正常，
     * Redisson 获取时隙锁时超时。
     */
    @Test
    void redissonTimeoutFallsBackToMysql() throws Exception {

        ReservationCreateDTO dto = newRequest();
        Reservation expected = mock(Reservation.class);

        when(expected.getId()).thenReturn(47L);

        when(stateStore.tryStart(
                eq(dto.getRequestId()),
                eq(USER_ID),
                eq(SLOT_ID),
                anyString()))
                .thenReturn(true);

        RLock lock = mock(RLock.class);

        when(redissonClient.getLock(
                "resq:lock:slot:" + SLOT_ID))
                .thenReturn(lock);

        when(lock.tryLock(2, TimeUnit.SECONDS))
                .thenThrow(new RedisTimeoutException(
                        "模拟 Redisson 命令超时"));

        when(reservationService.create(USER_ID, dto))
                .thenReturn(expected);

        Reservation actual = lockService.create(USER_ID, dto);

        assertSame(expected, actual);

        verify(reservationService).create(USER_ID, dto);

        // 未成功获得锁，不应该执行 unlock()
        verify(lock, never()).unlock();
    }

    /**
     * 场景 4：
     * MySQL 创建成功，
     * Redis markSuccess() 连接失败。
     *
     * 不应该影响已成功的预约结果。
     */
    @Test
    void redisMarkSuccessFailureKeepsMysqlResult()
            throws Exception {

        ReservationCreateDTO dto = newRequest();
        Reservation expected = mock(Reservation.class);

        when(expected.getId()).thenReturn(47L);

        when(stateStore.tryStart(
                eq(dto.getRequestId()),
                eq(USER_ID),
                eq(SLOT_ID),
                anyString()))
                .thenReturn(true);

        RLock lock = mock(RLock.class);

        when(redissonClient.getLock(
                "resq:lock:slot:" + SLOT_ID))
                .thenReturn(lock);

        when(lock.tryLock(2, TimeUnit.SECONDS))
                .thenReturn(true);

        when(lock.isHeldByCurrentThread())
                .thenReturn(true);

        when(reservationService.create(USER_ID, dto))
                .thenReturn(expected);

        when(stateStore.markSuccess(
                eq(dto.getRequestId()),
                eq(USER_ID),
                eq(SLOT_ID),
                anyString(),
                eq(47L)))
                .thenThrow(new RedisConnectionFailureException(
                        "模拟 Redis SUCCESS 写入失败"));

        Reservation actual = lockService.create(USER_ID, dto);

        // Redis 故障不能影响已成功的 MySQL 结果
        assertSame(expected, actual);

        verify(reservationService, times(1))
                .create(USER_ID, dto);

        // 正常获得的 Redisson 锁仍然需要释放
        verify(lock).unlock();
    }
}
