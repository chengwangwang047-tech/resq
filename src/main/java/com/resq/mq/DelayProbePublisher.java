package com.resq.mq;

import com.resq.config.ReservationTimeoutMqConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
@ConditionalOnProperty(
        name = "resq.day20.probe-enabled",
        havingValue = "true"
)
public class DelayProbePublisher {

    private static final Logger log =
            LoggerFactory.getLogger(DelayProbePublisher.class);

    private final RabbitTemplate rabbitTemplate;

    public DelayProbePublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void sendOneProbe() {

        String message = "day20-probe-" + UUID.randomUUID();

        Instant sendTime = Instant.now();

        rabbitTemplate.convertAndSend(
                ReservationTimeoutMqConfig.DELAY_EXCHANGE,
                ReservationTimeoutMqConfig.DELAY_ROUTING_KEY,
                message
        );

        log.info(
                "[DAY20 SEND CALLED] time={}, message={}",
                sendTime,
                message
        );
    }
}