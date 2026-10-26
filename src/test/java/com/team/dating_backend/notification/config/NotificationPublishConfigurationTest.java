package com.team.dating_backend.notification.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import tools.jackson.databind.json.JsonMapper;

@Timeout(15)
class NotificationPublishConfigurationTest {

    @Test
    void 종료할_때_Kafka와_통보_자원이_살아있는_동안_대기_중인_발행_작업을_처리한다() throws Exception {
        var properties = new NotificationPublishProperties();
        properties.setPoolSize(1);
        properties.setQueueCapacity(1);
        properties.setShutdownWaitSeconds(5);
        var context = new AnnotationConfigApplicationContext();
        context.registerBean(NotificationPublishProperties.class, () -> properties);
        context.registerBean(NotificationAlertProperties.class, NotificationAlertProperties::new);
        context.registerBean(JsonMapper.class, () -> JsonMapper.builder().build());
        context.register(NotificationPublishConfiguration.class, NotificationAlertConfiguration.class,
            KafkaBeans.class);
        context.refresh();
        var executor = context.getBean("notificationPublishExecutor", ThreadPoolTaskExecutor.class);
        var alert = context.getBean("notificationAlertExecutor", ThreadPoolTaskExecutor.class);
        var factory = context.getBean(TrackingProducerFactory.class);
        var httpClient = context.getBean("notificationDiscordHttpClient", HttpClient.class);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var alerted = new CountDownLatch(1);
        try (var closer = Executors.newSingleThreadExecutor()) {
            assertThat(executor.getCorePoolSize()).isEqualTo(1);
            assertThat(executor.getMaxPoolSize()).isEqualTo(1);
            assertThat(executor.getQueueCapacity()).isEqualTo(1);
            assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
            try (var otherContext = new AnnotationConfigApplicationContext()) {
                executor.onApplicationEvent(new ContextClosedEvent(otherContext));
                assertThat(executor.getThreadPoolExecutor().isShutdown()).isFalse();
            }
            executor.execute(() -> {
                started.countDown();
                await(release);
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            var queued = executor.submit(() -> {
                assertThat(factory.stopped.get()).isFalse();
                assertThat(alert.getThreadPoolExecutor().isShutdown()).isFalse();
                assertThat(httpClient.isTerminated()).isFalse();
                alert.execute(alerted::countDown);
            });
            var closed = closer.submit(context::close);
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                while (!executor.getThreadPoolExecutor().isShutdown()) {
                    Thread.sleep(1);
                }
            });
            assertThat(factory.stopped.get()).isFalse();
            release.countDown();
            queued.get(5, TimeUnit.SECONDS);
            closed.get(5, TimeUnit.SECONDS);

            assertThat(alerted.getCount()).isZero();
            assertThat(executor.getThreadPoolExecutor().isTerminated()).isTrue();
            assertThat(factory.stopped.get()).isTrue();
            assertThat(httpClient.awaitTermination(Duration.ofSeconds(5))).isTrue();
        } finally {
            release.countDown();
            context.close();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    static class TrackingProducerFactory extends DefaultKafkaProducerFactory<Object, Object> {
        final AtomicBoolean stopped = new AtomicBoolean();

        TrackingProducerFactory() {
            super(Map.of());
        }

        @Override
        public void stop() {
            stopped.set(true);
            super.stop();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class KafkaBeans {
        @Bean
        TrackingProducerFactory kafkaProducerFactory() {
            return new TrackingProducerFactory();
        }

        @Bean
        KafkaTemplate<Object, Object> kafkaTemplate(TrackingProducerFactory factory) {
            return new KafkaTemplate<>(factory);
        }
    }
}
