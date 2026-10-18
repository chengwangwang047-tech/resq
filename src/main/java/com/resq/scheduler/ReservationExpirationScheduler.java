package com.resq.scheduler;

import com.resq.mapper.ReservationMapper;
import com.resq.service.ReservationService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ReservationExpirationScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(
                    ReservationExpirationScheduler.class);

    private static final int BATCH_SIZE = 100;

    private final ReservationMapper reservationMapper;
    private final ReservationService reservationService;

    public ReservationExpirationScheduler(
            ReservationMapper reservationMapper,
            ReservationService reservationService) {

        this.reservationMapper = reservationMapper;
        this.reservationService = reservationService;
    }

    /**
     * Day20：预约超时补偿任务。
     *
     * 每隔 60 秒扫描一次 MySQL。
     * 首次启动等待 10 秒。
     */
    @Scheduled(
            fixedDelayString =
                    "${resq.reservation.expire-scan-interval-ms:60000}",
            initialDelayString =
                    "${resq.reservation.expire-scan-initial-delay-ms:10000}"
    )
    public void compensateExpiredReservations() {

        // 1. 查询已经到期的 PENDING 预约
        List<Long> ids =
                reservationMapper.findDuePendingReservationIds(
                        BATCH_SIZE);

        if (ids.isEmpty()) {
            return;
        }

        log.info(
                "[DAY20 EXPIRE SCAN] found={}",
                ids.size()
        );

        int expiredCount = 0;
        int skippedCount = 0;
        int failedCount = 0;

        // 2. 逐条执行已有的过期业务
        for (Long reservationId : ids) {

            try {
                boolean expired =
                        reservationService.expireIfPendingAndDue(
                                reservationId);

                if (expired) {
                    expiredCount++;
                } else {
                    skippedCount++;
                }

            } catch (Exception e) {

                failedCount++;

                log.error(
                        "[DAY20 EXPIRE SCAN FAILED] reservationId={}",
                        reservationId,
                        e
                );
            }
        }

        // 3. 记录本轮补偿结果
        log.info(
                "[DAY20 EXPIRE SCAN FINISHED] expired={}, skipped={}, failed={}",
                expiredCount,
                skippedCount,
                failedCount
        );
    }
}