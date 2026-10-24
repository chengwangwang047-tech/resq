package com.resq.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.resq.config.RabbitMQConfig;
import com.resq.event.ReservationCreatedEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class ReservationEventPublisher {

    private static final Logger log =
            LoggerFactory.getLogger(ReservationEventPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    public ReservationEventPublisher(
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper) {

        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
    }

    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT
    )
    public void onReservationCreated(
            ReservationCreatedEvent event) {

        try {
            String json =
                    objectMapper.writeValueAsString(event);

            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.EXCHANGE,
                    RabbitMQConfig.ROUTING_KEY,
                    json
            );

            log.info(
                    "[MQ SEND CALLED] reservationId={} status={}",
                    event.reservationId(),
                    event.status()
            );

        } catch (Exception e) {

            // 数据库事务已经提交，不能因此回滚预约。
            // Day21 再完善可靠投递与重试。
            log.error(
                    "[MQ SEND FAILED] reservationId={}",
                    event.reservationId(),
                    e
            );
        }
    }
}