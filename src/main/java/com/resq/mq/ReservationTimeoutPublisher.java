package com.resq.mq;

import com.resq.config.ReservationTimeoutMqConfig;
import com.resq.event.ReservationCreatedEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.MessageDeliveryMode;

@Component
public class ReservationTimeoutPublisher {

    private static final Logger log = LoggerFactory.getLogger(ReservationTimeoutPublisher.class);

    private final RabbitTemplate rabbitTemplate;

    private final ReservationTimeoutMqConfig timeoutMqConfig;
    private final ObjectMapper objectMapper;

    public ReservationTimeoutPublisher(
            RabbitTemplate rabbitTemplate,
            ReservationTimeoutMqConfig timeoutMqConfig, ObjectMapper objectMapper) {

        this.rabbitTemplate = rabbitTemplate;
        this.timeoutMqConfig = timeoutMqConfig;
        this.objectMapper = objectMapper;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReservationCreated(ReservationCreatedEvent event) {

        // 只处理待确认预约的创建事件
        if (!"PENDING".equals(event.status())) {
            return;
        }

        Long reservationId = event.reservationId();

        if (reservationId == null) {
            log.error("[DAY21 TIMEOUT SEND SKIPPED] reservationId is null");
            return;
        }

        try {
            // 本阶段只发送预约 ID
            // 1. 构造超时事件
            ReservationTimeoutEvent timeoutEvent = ReservationTimeoutEvent.forReservation(reservationId);

            // 2. 将 Java 对象序列化为 JSON
            String json = objectMapper.writeValueAsString(timeoutEvent);

            // 3. 发送持久化消息
            rabbitTemplate.convertAndSend(
                    ReservationTimeoutMqConfig.DELAY_EXCHANGE,
                    timeoutMqConfig.delayRoutingKey(),
                    json,
                    message -> {
                        message.getMessageProperties()
                                .setDeliveryMode(
                                        MessageDeliveryMode.PERSISTENT);
                        return message;
                    });

            log.info(
                    "[DAY21 TIMEOUT SEND CALLED] eventId={}, reservationId={}",
                    timeoutEvent.eventId(),
                    reservationId);

        } catch (Exception e) {
            log.error(
                    "[DAY21 TIMEOUT SEND FAILED] reservationId={}",
                    reservationId,
                    e);
        }
    }
}