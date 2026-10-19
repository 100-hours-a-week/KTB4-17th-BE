package com.team.dating_backend;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

public final class RabbitTestSupport {

    private RabbitTestSupport() {}

    public static GenericContainer<?> container() {
        return new GenericContainer<>("rabbitmq:4.3-management")
            .withEnv("RABBITMQ_DEFAULT_USER", "migration")
            .withEnv("RABBITMQ_DEFAULT_PASS", "migration-local")
            .withCopyToContainer(Transferable.of("[rabbitmq_management,rabbitmq_stomp]."),
                "/etc/rabbitmq/enabled_plugins")
            .withExposedPorts(5672, 61613)
            .waitingFor(Wait.forListeningPorts(5672, 61613)
                .withStartupTimeout(Duration.ofMinutes(2)));
    }

    public static void properties(DynamicPropertyRegistry registry, GenericContainer<?> rabbit) {
        registry.add("spring.rabbitmq.host", rabbit::getHost);
        registry.add("spring.rabbitmq.port", () -> rabbit.getMappedPort(5672));
        registry.add("spring.rabbitmq.username", () -> "migration");
        registry.add("spring.rabbitmq.password", () -> "migration-local");
        registry.add("spring.rabbitmq.publisher-confirm-type", () -> "correlated");
        registry.add("app.chat.rabbit.host", rabbit::getHost);
        registry.add("app.chat.rabbit.stomp-port", () -> rabbit.getMappedPort(61613));
    }

    public static WebSocketStompClient webSocketClient() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.initialize();
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient()) {
            @Override
            public void stop() {
                super.stop();
                scheduler.shutdown();
            }
        };
        client.setTaskScheduler(scheduler);
        return client;
    }

    public static void subscribe(
        StompSession session, String destination, StompFrameHandler handler) {
        StompHeaders headers = new StompHeaders();
        headers.setDestination(destination);
        headers.setReceipt(UUID.randomUUID().toString());
        CountDownLatch received = new CountDownLatch(1);
        session.subscribe(headers, handler).addReceiptTask(received::countDown);
        try {
            if (!received.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("RabbitMQ did not confirm the subscription");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Subscription interrupted", exception);
        }
    }
}
