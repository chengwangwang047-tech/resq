
package com.resq.service;

import com.resq.cache.ReservationRequestStateStore;
import com.resq.domain.Reservation;
import com.resq.dto.ReservationCreateDTO;
import com.resq.exception.ReservationBusyException;
import com.resq.exception.ReservationConflictException;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisConnectionException;
import org.redisson.client.RedisTimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class ReservationLockService {

    private static final Logger log = LoggerFactory.getLogger(ReservationLockService.class);

    private final RedissonClient redissonClient;
    private final ReservationService reservationService;
    private final ReservationRequestStateStore requestStateStore;
    private final ReservationRateLimiter reservationRateLimiter;

    public ReservationLockService(
            RedissonClient redissonClient,
            ReservationService reservationService,
            ReservationRequestStateStore requestStateStore,
            ReservationRateLimiter reservationRateLimiter) {

        this.redissonClient = redissonClient;
        this.reservationService = reservationService;
        this.requestStateStore = requestStateStore;
        this.reservationRateLimiter = reservationRateLimiter;
    }

    /**
     * Day17：原有时隙锁流程。
     *
     * 同一个 slotId 使用同一把 Redisson 锁。
     * MySQL 事务仍由 ReservationService.create() 管理。
     */
    private Reservation createWithSlotLock(
            Long userId, ReservationCreateDTO dto) {

        String lockKey = "resq:lock:slot:" + dto.getSlotId();

        RLock lock = redissonClient.getLock(lockKey);
        boolean locked = false;
        RuntimeException primaryFailure = null;

        try {
            locked = lock.tryLock(2, TimeUnit.SECONDS);

            if (!locked) {
                throw new ReservationBusyException(
                        "预约繁忙，请稍后重试");
            }

            try {
                return reservationService.create(userId, dto);

            } catch (DuplicateKeyException e) {

                // 此时事务代理已经完成回滚
                Reservation existing = reservationService.findExistingForRetry(
                        userId, dto);

                if (existing != null) {
                    return existing;
                }

                throw e;
            }

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();

            throw new ReservationBusyException(
                    "等待预约锁时被中断", e);

        } catch (RuntimeException e) {
            primaryFailure = e;
            throw e;
        } finally {
            if (locked) {
                try {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                } catch (RuntimeException cleanupFailure) {
                    if (primaryFailure == null) {
                        // Preserve the existing MySQL recovery path after an uncertain success.
                        throw cleanupFailure;
                    }
                    if (primaryFailure != cleanupFailure) {
                        primaryFailure.addSuppressed(cleanupFailure);
                    }
                    log.warn("预约锁清理失败，已保留原异常，slotId={}", dto.getSlotId());
                }
            }
        }
    }

    /**
     * Day18：MySQL 兜底流程。
     *
     * 不使用 Redis 或 Redisson。
     * 依赖 MySQL 条件更新、事务和 request_id 唯一索引。
     */
    private Reservation createWithoutRedis(
            Long userId, ReservationCreateDTO dto) {

        try {
            return reservationService.create(userId, dto);

        } catch (DuplicateKeyException e) {

            Reservation existing = reservationService.findExistingForRetry(
                    userId, dto);

            if (existing != null) {
                return existing;
            }

            throw e;

        } catch (RuntimeException e) {

            // 结果不确定时，重新查询 MySQL
            try {
                Reservation recovered = reservationService.findExistingForRetry(
                        userId, dto);

                if (recovered != null) {
                    return recovered;
                }

            } catch (ReservationConflictException conflict) {

                throw conflict;

            } catch (RuntimeException queryError) {

                e.addSuppressed(queryError);
            }

            throw e;
        }
    }

    /**
     * Day18：优先使用时隙锁。
     * Redisson 连接异常时转为 MySQL 兜底。
     */
    private Reservation createWithSlotLockOrFallback(
            Long userId, ReservationCreateDTO dto) {

        try {
            return createWithSlotLock(userId, dto);

        } catch (RedisConnectionException | RedisTimeoutException e) {

            log.warn("Redisson 不可用，切换 MySQL 兜底", e);

            return createWithoutRedis(userId, dto);
        }
    }

    /**
     * Day18：对外提供的创建预约入口。
     *
     * MySQL 历史查询
     * -> Redis 请求状态
     * -> Redisson 时隙锁
     * -> MySQL 事务
     * -> Redis SUCCESS
     */
    public Reservation create(
            Long userId, ReservationCreateDTO dto) {

        // 1. 参数检查
        if (userId == null || userId <= 0
                || dto == null
                || dto.getSlotId() == null
                || dto.getSlotId() <= 0) {

            throw new IllegalArgumentException("预约参数不合法");
        }

        String requestId = dto.getRequestId();

        if (requestId == null || requestId.isBlank()
                || requestId.length() > 100) {

            throw new IllegalArgumentException("requestId 不合法");
        }

        // 2. 优先查询 MySQL，处理已经完成的请求
        Reservation existing = reservationService.findExistingForRetry(userId, dto);

        if (existing != null) {
            return existing;
        }

        // Day22：新预约尝试执行用户级限流
        reservationRateLimiter.check(userId);

        // 3. 生成本次处理的 ownerToken
        String ownerToken = UUID.randomUUID().toString();

        // 4. Redis SET NX：尝试登记 PROCESSING
        boolean started;

        try {
            started = requestStateStore.tryStart(
                    requestId,
                    userId,
                    dto.getSlotId(),
                    ownerToken);

        } catch (RedisConnectionFailureException | QueryTimeoutException e) {

            log.warn("Redis 幂等状态不可用，切换 MySQL 兜底", e);

            return createWithoutRedis(userId, dto);
        }

        // 5. 未获得处理资格：处理重复请求
        if (!started) {

            String state;

            try {
                state = requestStateStore.getState(requestId);

            } catch (RedisConnectionFailureException | QueryTimeoutException e) {

                // Day18 修正：
                // Redis 状态读取失败，也允许 MySQL 兜底
                log.warn("Redis 状态读取失败，切换 MySQL 兜底", e);

                return createWithoutRedis(userId, dto);
            }

            // 再查 MySQL，避免错过刚提交的预约
            Reservation previous = reservationService.findExistingForRetry(userId, dto);

            if (previous != null) {
                return previous;
            }

            // 6. 判断 Redis 中是否记录了不同的业务参数
            if (state != null) {

                // 按真正的竖线分割
                String[] parts = state.split("\\|", -1);

                // PROCESSING|ownerToken|userId|slotId
                // SUCCESS|reservationId|userId|slotId
                if (parts.length == 4
                        && ("PROCESSING".equals(parts[0])
                                || "SUCCESS".equals(parts[0]))) {

                    boolean sameUser = String.valueOf(userId).equals(parts[2]);

                    boolean sameSlot = String.valueOf(dto.getSlotId()).equals(parts[3]);

                    if (!sameUser || !sameSlot) {

                        throw new ReservationConflictException(
                                "requestId 已用于其他预约操作");
                    }
                }
            }

            // 不重复创建
            throw new ReservationBusyException(
                    "相同预约请求正在处理，请稍后重试");
        }

        // 7. 获得 Redis 处理资格，执行预约
        try {

            Reservation result = createWithSlotLockOrFallback(userId, dto);

            // MySQL 事务已经正常完成
            // 尝试把 PROCESSING 改成 SUCCESS
            try {

                boolean marked = requestStateStore.markSuccess(
                        requestId,
                        userId,
                        dto.getSlotId(),
                        ownerToken,
                        result.getId());

                if (!marked) {
                    log.warn("SUCCESS 状态更新未生效: {}", requestId);
                }

            } catch (RuntimeException redisError) {

                // MySQL 已经成功，不能因为 Redis 故障返回失败
                log.warn("Redis SUCCESS 更新失败: {}",
                        requestId, redisError);
            }

            return result;

        } catch (ReservationConflictException | ReservationBusyException e) {

            // 8. 明确业务失败：清理自己创建的 PROCESSING
            try {
                requestStateStore.releaseProcessing(
                        requestId,
                        userId,
                        dto.getSlotId(),
                        ownerToken);

            } catch (RuntimeException redisError) {
                log.warn("Redis PROCESSING 清理失败: {}",
                        requestId, redisError);
            }

            throw e;

        } catch (RuntimeException e) {

            // 9. 未知异常：MySQL 可能已经提交
            Reservation recovered;

            try {
                recovered = reservationService.findExistingForRetry(userId, dto);

            } catch (ReservationConflictException conflict) {

                // requestId 确实属于其他业务操作
                try {
                    requestStateStore.releaseProcessing(
                            requestId,
                            userId,
                            dto.getSlotId(),
                            ownerToken);

                } catch (RuntimeException redisError) {
                    log.warn("Redis PROCESSING 清理失败", redisError);
                }

                throw conflict;

            } catch (RuntimeException queryError) {

                // MySQL 回查也失败：保留原始异常
                e.addSuppressed(queryError);
                throw e;
            }

            // 10. MySQL 已存在该请求的预约，恢复成功响应
            if (recovered != null) {

                try {
                    boolean marked = requestStateStore.markSuccess(
                            requestId,
                            userId,
                            dto.getSlotId(),
                            ownerToken,
                            recovered.getId());

                    if (!marked) {
                        log.warn("恢复 SUCCESS 状态未生效: {}", requestId);
                    }

                } catch (RuntimeException redisError) {
                    log.warn("恢复 SUCCESS 失败: {}",
                            requestId, redisError);
                }

                return recovered;
            }

            // 11. 仍无法确认结果
            // 不盲目清理 PROCESSING，让 TTL 到期后重新判断
            throw e;
        }
    }
}
