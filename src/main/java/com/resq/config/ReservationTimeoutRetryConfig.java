package com.resq.config;

import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;

import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ReservationTimeoutRetryConfig {

    @Bean("reservationTimeoutListenerFactory")
    public SimpleRabbitListenerContainerFactory
            reservationTimeoutListenerFactory(
                    SimpleRabbitListenerContainerFactoryConfigurer configurer,
                    ConnectionFactory connectionFactory) {

        // 1. 创建专用监听容器工厂
        SimpleRabbitListenerContainerFactory factory =
                new SimpleRabbitListenerContainerFactory();

        // 2. 继承 Spring Boot 原有 RabbitMQ 配置
        configurer.configure(factory, connectionFactory);

        // 3. 配置消费失败重试
        factory.setAdviceChain(
                RetryInterceptorBuilder.stateless()
                        .maxAttempts(3)
                        .backOffOptions(1000, 2.0, 2000)
                        .recoverer(
                                new RejectAndDontRequeueRecoverer())
                        .build()
        );

        return factory;
    }
}