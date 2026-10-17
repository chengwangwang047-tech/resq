package com.resq.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.resq.config.RabbitMQConfig;
import com.resq.event.ReservationCreatedEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class NotificationConsumer {

    private static final Logger log =
            LoggerFactory.getLogger(NotificationConsumer.class);

    private final ObjectMapper objectMapper;

    public NotificationConsumer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = RabbitMQConfig.NOTIFICATION_QUEUE)
    public void handleReservationCreated(Message message)
            throws IOException {

        // 1. 读取 RabbitMQ 消息正文
        String json = new String(
                message.getBody(),
                StandardCharsets.UTF_8
        );

        // 2. JSON 转回 Java 事件对象
        ReservationCreatedEvent event =
                objectMapper.readValue(
                        json,
                        ReservationCreatedEvent.class
                );

        // 3. 模拟异步通知
        log.info(
                "[MQ RECEIVED] reservationId={} status={} queue={} key={}",
                event.reservationId(),
                event.status(),
                message.getMessageProperties().getConsumerQueue(),
                message.getMessageProperties().getReceivedRoutingKey()
        );

        log.info(
                "[NOTIFICATION] 预约 {} 已创建，当前状态为 {}",
                event.reservationId(),
                event.status()
        );
    }
}