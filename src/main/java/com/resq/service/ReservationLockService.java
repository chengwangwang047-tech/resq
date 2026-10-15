package com.resq.service;

import com.resq.domain.Reservation;
import com.resq.dto.ReservationCreateDTO;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import com.resq.exception.ReservationBusyException;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class ReservationLockService {

    private final RedissonClient redissonClient;
    private final ReservationService reservationService;

    public ReservationLockService(
            RedissonClient redissonClient,
            ReservationService reservationService) {

        this.redissonClient = redissonClient;
        this.reservationService = reservationService;
    }

    public Reservation create(
            Long userId,
            ReservationCreateDTO dto) {

        if (dto == null || dto.getSlotId() == null
                || dto.getSlotId() <= 0) {
            throw new IllegalArgumentException("时隙 ID 不合法");
        }

        // ① 根据时隙 ID 获取锁对象
        String lockKey = "resq:lock:slot:" + dto.getSlotId();
        RLock lock = redissonClient.getLock(lockKey);

        boolean locked = false;

        try {
            // ② 最多等待 2 秒获取锁
            locked = lock.tryLock(2, TimeUnit.SECONDS);

            if (!locked) {
                throw new ReservationBusyException("预约繁忙，请稍后重试");
            }

            // ③ 调用原有的预约事务方法
            return reservationService.create(userId, dto);

        } catch (InterruptedException e) {

    Thread.currentThread().interrupt();

    throw new ReservationBusyException(
            "等待预约锁时被中断", e);
} finally {

            // ④ 仅在当前线程仍持有锁时尝试释放
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}