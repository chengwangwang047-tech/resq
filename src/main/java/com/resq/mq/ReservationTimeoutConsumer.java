package com.resq.mq;

import com.resq.config.ReservationTimeoutMqConfig;
import com.resq.service.ReservationService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class ReservationTimeoutConsumer {

    private static final Logger log =
            LoggerFactory.getLogger(
                    ReservationTimeoutConsumer.class);

    private final ReservationService reservationService;

    public ReservationTimeoutConsumer(
            ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @RabbitListener(
            queues = ReservationTimeoutMqConfig.DEAD_QUEUE
    )
    public void receive(String message) {

        // 1. 将 RabbitMQ 消息解析为预约 ID
        Long reservationId;

        try {
            reservationId = Long.valueOf(message.trim());
        } catch (NumberFormatException e) {
            log.error(
                    "[DAY20 INVALID MESSAGE] message={}",
                    message
            );
            throw new IllegalArgumentException(
                    "非法预约超时消息", e);
        }

        log.info(
                "[DAY20 TIMEOUT RECEIVED] reservationId={}",
                reservationId
        );

        // 2. 调用 Service 执行过期释放
        boolean expired = reservationService
                .expireIfPendingAndDue(reservationId);

        // 3. 记录处理结果
        if (expired) {
            log.info(
                    "[DAY20 TIMEOUT EXPIRED] reservationId={}",
                    reservationId
            );
        } else {
            log.info(
                    "[DAY20 TIMEOUT NO TRANSITION] reservationId={}",
                    reservationId
            );
        }
    }
}