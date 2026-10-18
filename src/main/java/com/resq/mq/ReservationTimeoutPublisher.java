package com.resq.mq;

import com.resq.config.ReservationTimeoutMqConfig;
import com.resq.event.ReservationCreatedEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class ReservationTimeoutPublisher {

    private static final Logger log = LoggerFactory.getLogger(ReservationTimeoutPublisher.class);

    private final RabbitTemplate rabbitTemplate;

    private final ReservationTimeoutMqConfig timeoutMqConfig;

    public ReservationTimeoutPublisher(
            RabbitTemplate rabbitTemplate,
            ReservationTimeoutMqConfig timeoutMqConfig) {

        this.rabbitTemplate = rabbitTemplate;
        this.timeoutMqConfig = timeoutMqConfig;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReservationCreated(ReservationCreatedEvent event) {

        // 只处理待确认预约的创建事件
        if (!"PENDING".equals(event.status())) {
            return;
        }

        Long reservationId = event.reservationId();

        if (reservationId == null) {
            log.error("[DAY20 TIMEOUT SEND SKIPPED] reservationId is null");
            return;
        }

        try {
            // 本阶段只发送预约 ID
            String message = String.valueOf(reservationId);

            rabbitTemplate.convertAndSend(
                    ReservationTimeoutMqConfig.DELAY_EXCHANGE,
                    timeoutMqConfig.delayRoutingKey(),
                    message);
            log.info(
                    "[DAY20 TIMEOUT SEND CALLED] reservationId={}",
                    reservationId);

        } catch (Exception e) {
            log.error(
                    "[DAY20 TIMEOUT SEND FAILED] reservationId={}",
                    reservationId,
                    e);
        }
    }
}