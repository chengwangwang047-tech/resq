package com.resq.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.resq.config.ReservationTimeoutMqConfig;
import com.resq.config.ReservationTimeoutRetryConfig;
import com.resq.service.ReservationTimeoutConsumeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Opt-in only: use a disposable broker; never loads application connection settings. */
@EnabledIfEnvironmentVariable(named = "RESQ_P0_ISOLATED_BROKER", matches = "[a-f0-9]{32}")
@org.junit.jupiter.api.Tag("integration")
class TimeoutFailureRoutingIT {
    @Test void retryExhaustionRetainsMessagesAndSuccessIsAcknowledged() throws Exception {
        int port = Integer.parseInt(System.getenv("RESQ_P0_RABBIT_PORT"));
        assertTrue(port > 1024 && port != 5672, "Dedicated loopback port required");
        CachingConnectionFactory connection = new CachingConnectionFactory("127.0.0.1", port);
        connection.setUsername(System.getenv("RESQ_P0_RABBIT_USER"));
        connection.setPassword(System.getenv("RESQ_P0_RABBIT_PASSWORD"));
        SimpleMessageListenerContainer listener = null;
        try {
            RabbitAdmin admin = new RabbitAdmin(connection);
            var topology = new ReservationTimeoutMqConfig(600).reservationTimeoutTopology();
            for (var declaration : topology.getDeclarables()) {
                if (declaration instanceof org.springframework.amqp.core.Exchange exchange) admin.declareExchange(exchange);
                if (declaration instanceof org.springframework.amqp.core.Queue queue) admin.declareQueue(queue);
            }
            for (var declaration : topology.getDeclarables()) {
                if (declaration instanceof org.springframework.amqp.core.Binding binding) admin.declareBinding(binding);
            }
            var configurer = new SimpleRabbitListenerContainerFactoryConfigurer(new RabbitProperties());
            var factory = new ReservationTimeoutRetryConfig().reservationTimeoutListenerFactory(configurer, connection);
            listener = factory.createListenerContainer();
            listener.setQueueNames(ReservationTimeoutMqConfig.DEAD_QUEUE);
            listener.setAcknowledgeMode(AcknowledgeMode.AUTO);
            var service = mock(ReservationTimeoutConsumeService.class);
            when(service.consume(any())).thenThrow(new IllegalStateException("Synthetic database failure"));
            var consumer = new ReservationTimeoutConsumer(service, new ObjectMapper());
            AtomicInteger attempts = new AtomicInteger();
            listener.setMessageListener((org.springframework.amqp.core.MessageListener) message -> {
                attempts.incrementAndGet();
                consumer.receive(new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8));
            });
            listener.start();
            RabbitTemplate template = new RabbitTemplate(connection);
            template.convertAndSend(ReservationTimeoutMqConfig.DEAD_EXCHANGE,
                    ReservationTimeoutMqConfig.DEAD_ROUTING_KEY,
                    "{\"eventId\":\"reservation-expire-1\",\"reservationId\":1,\"eventType\":\"RESERVATION_EXPIRE\"}");
            Message failed = template.receive(ReservationTimeoutMqConfig.FAILED_QUEUE, 15000);
            assertNotNull(failed, "Failure must be retained after retries");
            assertEquals(3, attempts.get());
            verify(service, times(3)).consume(any());
            assertTrue(failed.getMessageProperties().getHeaders().containsKey("x-death"));
            assertEquals(1L, new ObjectMapper().readTree(failed.getBody()).get("reservationId").asLong());

            attempts.set(0);
            template.convertAndSend(ReservationTimeoutMqConfig.DEAD_EXCHANGE,
                    ReservationTimeoutMqConfig.DEAD_ROUTING_KEY, "invalid-json");
            Message malformed = template.receive(ReservationTimeoutMqConfig.FAILED_QUEUE, 15000);
            assertNotNull(malformed);
            assertEquals(3, attempts.get());
            assertEquals("invalid-json", new String(malformed.getBody(), java.nio.charset.StandardCharsets.UTF_8));

            reset(service);
            attempts.set(0);
            when(service.consume(any())).thenReturn(ReservationTimeoutConsumeService.ConsumeResult.NO_TRANSITION);
            template.convertAndSend(ReservationTimeoutMqConfig.DEAD_EXCHANGE,
                    ReservationTimeoutMqConfig.DEAD_ROUTING_KEY, "2");
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(service).consume(any()));
            assertEquals(1, attempts.get());
            assertNull(template.receive(ReservationTimeoutMqConfig.FAILED_QUEUE, 500));
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertEquals(0, admin.getQueueInfo(ReservationTimeoutMqConfig.DEAD_QUEUE).getMessageCount()));
        } finally {
            if (listener != null) listener.stop();
            connection.destroy();
        }
    }
}
