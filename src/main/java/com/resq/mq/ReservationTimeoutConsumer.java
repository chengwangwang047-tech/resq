package com.resq.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resq.config.ReservationTimeoutMqConfig;
import com.resq.service.ReservationTimeoutConsumeService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

@Component
public class ReservationTimeoutConsumer {

    private static final Logger log = LoggerFactory.getLogger(
            ReservationTimeoutConsumer.class);

    private final ReservationTimeoutConsumeService consumeService;
    private final ObjectMapper objectMapper;

    public ReservationTimeoutConsumer(
            ReservationTimeoutConsumeService consumeService,
            ObjectMapper objectMapper) {

        this.consumeService = consumeService;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = ReservationTimeoutMqConfig.DEAD_QUEUE, containerFactory = "reservationTimeoutListenerFactory")
    public void receive(String message) {

        ReservationTimeoutEvent event = parseEvent(message);

        try {
            var result = consumeService.consume(event);

            log.info(
                    "[DAY21 CONSUMED] eventId={}, result={}",
                    event.eventId(),
                    result);

        } catch (DuplicateKeyException e) {

            // consume() 的事务已经退出并回滚
            // 唯一键冲突表示此 eventId 已经成功提交过
            log.info(
                    "[DAY21 DUPLICATE] eventId={}",
                    event.eventId());
        }

        // 正常返回，由 Spring AMQP AUTO 模式发送 ACK
    }

    private ReservationTimeoutEvent parseEvent(String message) {

        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("空消息");
        }

        String body = message.trim();

        // 兼容 Day20 的旧消息：仅包含预约 ID
        if (body.matches("\\d+")) {

            Long reservationId = Long.valueOf(body);

            return ReservationTimeoutEvent.forReservation(
                    reservationId);
        }

        // Day21 新消息：JSON 格式
        try {
            return objectMapper.readValue(
                    body,
                    ReservationTimeoutEvent.class);

        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "预约超时消息 JSON 解析失败", e);
        }
    }
}