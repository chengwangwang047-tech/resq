package com.resq.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE =
            "resq.reservation.exchange";

    public static final String NOTIFICATION_QUEUE =
            "resq.notification.queue";

    public static final String ROUTING_KEY =
            "reservation.created";

    // 1. 创建 Direct Exchange
    @Bean
    public DirectExchange reservationExchange() {
        return new DirectExchange(EXCHANGE);
    }

    // 2. 创建持久化 Queue
    @Bean
    public Queue notificationQueue() {
        return QueueBuilder.durable(NOTIFICATION_QUEUE).build();
    }

    // 3. 创建 Binding
    @Bean
    public Binding notificationBinding(
            @Qualifier("notificationQueue") Queue queue,
            DirectExchange reservationExchange) {

        return BindingBuilder.bind(queue)
                .to(reservationExchange)
                .with(ROUTING_KEY);
    }
}