package com.resq.config;

import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ReservationTimeoutMqConfig {

        public static final String DELAY_EXCHANGE = "resq.reservation.timeout.delay.exchange";

        public static final String DELAY_QUEUE = "resq.reservation.timeout.delay.queue";

        public static final String DELAY_ROUTING_KEY = "reservation.timeout.wait";

        public static final String DEAD_EXCHANGE = "resq.reservation.timeout.dead.exchange";

        public static final String DEAD_QUEUE = "resq.reservation.timeout.dead.queue";

        public static final String DEAD_ROUTING_KEY = "reservation.timeout.expired";
        // Day21：消费失败隔离队列
        public static final String FAILED_EXCHANGE = "resq.reservation.timeout.failed.exchange";

        public static final String FAILED_QUEUE = "resq.reservation.timeout.failed.queue";

        public static final String FAILED_ROUTING_KEY = "reservation.timeout.failed";
        // 与 ReservationService 共用同一个配置项
        private final long confirmTimeoutSeconds;

        public ReservationTimeoutMqConfig(
                        @Value("${resq.reservation.confirm-timeout-seconds:600}") long confirmTimeoutSeconds) {

                if (confirmTimeoutSeconds <= 0
                                || confirmTimeoutSeconds > Integer.MAX_VALUE / 1000L) {
                        throw new IllegalArgumentException(
                                        "预约确认期限超出 RabbitMQ TTL 支持范围");
                }

                this.confirmTimeoutSeconds = confirmTimeoutSeconds;
        }

        // 根据期限生成不同的等待队列名称
        public String delayQueueName() {
                return DELAY_QUEUE + "." + confirmTimeoutSeconds + "s";
        }

        // 根据期限生成不同的路由键
        public String delayRoutingKey() {
                return DELAY_ROUTING_KEY + "." + confirmTimeoutSeconds + "s";
        }

        @Bean
        public Declarables reservationTimeoutTopology() {

                DirectExchange delayExchange = new DirectExchange(DELAY_EXCHANGE);

                DirectExchange deadExchange = new DirectExchange(DEAD_EXCHANGE);

                // 秒转换为毫秒
                int ttlMillis = Math.toIntExact(
                                confirmTimeoutSeconds * 1000L);

                Queue delayQueue = QueueBuilder
                                .durable(delayQueueName())
                                .ttl(ttlMillis)
                                .deadLetterExchange(DEAD_EXCHANGE)
                                .deadLetterRoutingKey(DEAD_ROUTING_KEY)
                                .build();

                Queue deadQueue = QueueBuilder
                                .durable(DEAD_QUEUE)
                                .build();
                // 消费失败专用 Exchange
                DirectExchange failedExchange = new DirectExchange(FAILED_EXCHANGE);

                // 消费失败专用 Queue
                Queue failedQueue = QueueBuilder
                                .durable(FAILED_QUEUE)
                                .build();

                return new Declarables(
                                delayExchange,
                                deadExchange,
                                failedExchange,

                                delayQueue,
                                deadQueue,
                                failedQueue,

                                BindingBuilder.bind(delayQueue)
                                                .to(delayExchange)
                                                .with(delayRoutingKey()),

                                BindingBuilder.bind(deadQueue)
                                                .to(deadExchange)
                                                .with(DEAD_ROUTING_KEY),

                                BindingBuilder.bind(failedQueue)
                                                .to(failedExchange)
                                                .with(FAILED_ROUTING_KEY));
        }
}