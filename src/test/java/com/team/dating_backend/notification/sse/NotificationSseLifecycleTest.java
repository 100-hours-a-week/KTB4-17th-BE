package com.team.dating_backend.notification.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.any;

import com.team.dating_backend.notification.config.NotificationDeliveryProperties;
import com.team.dating_backend.notification.config.NotificationKafkaProperties;
import com.team.dating_backend.notification.enums.NotificationErrorCode;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class NotificationSseLifecycleTest {

    private final NotificationDeliveryReadinessTracker tracker = new NotificationDeliveryReadinessTracker(
        kafkaProperties());
    private final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();

    @Test
    void 활성_delivery는_준비와_실행_상태를_함께_검사하고_drain_후에는_다시_열리지_않는다() {
        var registry = new NotificationEmitterRegistry(mock(NotificationSseObservation.class));
        var lifecycle = lifecycle(true, registry);
        assertNotReady(lifecycle);
        var endpoints = mock(KafkaListenerEndpointRegistry.class);
        var container = mock(MessageListenerContainer.class);
        beans.registerSingleton("endpoints", endpoints);
        when(endpoints.getListenerContainer("notificationDeliveryConsumer")).thenReturn(container);
        ready();
        assertNotReady(lifecycle);
        when(container.isRunning()).thenReturn(true);
        lifecycle.assertAcceptingSubscriptions();
        when(container.isPauseRequested()).thenReturn(true);
        assertNotReady(lifecycle);
        when(container.isPauseRequested()).thenReturn(false);
        lifecycle.beginDrain();
        ready();
        assertNotReady(lifecycle);
        assertThat(registry.snapshotAll()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 최종_등록은_drain이나_준비_무효화와_직렬화하고_기존_연결은_유지한다(boolean drain) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var registry = new NotificationEmitterRegistry(mock(NotificationSseObservation.class)) {
            @Override
            public boolean register(NotificationSseConnection connection) {
                entered.countDown();
                try {
                    assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
                return super.register(connection);
            }
        };
        var endpoints = mock(KafkaListenerEndpointRegistry.class);
        var container = mock(MessageListenerContainer.class);
        beans.registerSingleton("endpoints", endpoints);
        when(endpoints.getListenerContainer("notificationDeliveryConsumer")).thenReturn(container);
        when(container.isRunning()).thenReturn(true);
        var lifecycle = lifecycle(true, registry);
        ready();
        var connection = connection("first");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var registration = executor.submit(() -> lifecycle.registerIfAccepting(connection));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var invalidationStarted = new CountDownLatch(1);
            var invalidation = executor.submit(() -> {
                invalidationStarted.countDown();
                if (drain) {
                    lifecycle.beginDrain();
                } else {
                    tracker.invalidateAll();
                }
            });
            try {
                assertThat(invalidationStarted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> invalidation.get(100, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            } finally {
                release.countDown();
            }
            assertThat(registration.get(5, TimeUnit.SECONDS)).isTrue();
            invalidation.get(5, TimeUnit.SECONDS);
            assertNotReady(lifecycle);
            assertThatThrownBy(() -> lifecycle.registerIfAccepting(connection("second")))
                .isInstanceOf(NotificationBusinessException.class);
            assertThat(registry.snapshotAll()).containsExactly(connection);
            assertThat(connection.isClosed()).isFalse();
        }
    }

    @Test
    void 지표는_disabled_호환과_준비_수용_연결_상태를_구분해_현재_값을_반영한다() {
        var registry = new NotificationEmitterRegistry(mock(NotificationSseObservation.class));
        var lifecycle = lifecycle(false, registry);
        var meters = new SimpleMeterRegistry();
        try {
            new NotificationSseMetrics(tracker, lifecycle, registry).bindTo(meters);
            assertThat(meters.get("notification.delivery.ready").gauge().value()).isZero();
            assertThat(meters.get("notification.sse.accepting").gauge().value()).isEqualTo(1);
            var connection = connection("first");
            assertThat(lifecycle.registerIfAccepting(connection)).isTrue();
            ready();
            lifecycle.beginDrain();
            assertThat(meters.get("notification.delivery.ready").gauge().value()).isEqualTo(1);
            assertThat(meters.get("notification.sse.accepting").gauge().value()).isZero();
            assertThat(meters.get("notification.sse.connections").gauge().value()).isEqualTo(1);
            assertThat(meters.get("notification.sse.connections").gauge().getId().getTags()).isEmpty();
            registry.remove(connection);
            assertThat(meters.get("notification.sse.connections").gauge().value()).isZero();
        } finally {
            meters.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 등록_관측이_지연되어도_drain과_준비_무효화는_즉시_진행한다(boolean drain) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var observation = mock(NotificationSseObservation.class);
        doAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(observation).connectionRegistered(any());
        var registry = new NotificationEmitterRegistry(observation);
        var endpoints = mock(KafkaListenerEndpointRegistry.class);
        var container = mock(MessageListenerContainer.class);
        beans.registerSingleton("endpoints", endpoints);
        when(endpoints.getListenerContainer("notificationDeliveryConsumer")).thenReturn(container);
        when(container.isRunning()).thenReturn(true);
        ready();
        var lifecycle = lifecycle(true, registry);
        var first = connection("observed");
        try (var executor = Executors.newFixedThreadPool(2)) {
            try {
                var registration = executor.submit(() -> lifecycle.registerIfAccepting(first));
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                executor.submit(() -> {
                    if (drain) {
                        lifecycle.beginDrain();
                    } else {
                        tracker.invalidateAll();
                    }
                }).get(2, TimeUnit.SECONDS);
                assertNotReady(lifecycle);
                assertThatThrownBy(() -> lifecycle.registerIfAccepting(connection("rejected")))
                    .isInstanceOf(NotificationBusinessException.class);
                release.countDown();
                assertThat(registration.get(5, TimeUnit.SECONDS)).isTrue();
                verify(observation, times(1)).connectionRegistered(first);
                assertThat(registry.snapshotAll()).containsExactly(first);
            } finally {
                release.countDown();
            }
        }
    }

    private NotificationSseLifecycle lifecycle(boolean enabled, NotificationEmitterRegistry registry) {
        var properties = new NotificationDeliveryProperties();
        properties.setEnabled(enabled);
        return new NotificationSseLifecycle(properties, tracker, registry,
            beans.getBeanProvider(KafkaListenerEndpointRegistry.class));
    }

    private void ready() {
        tracker.invalidateAll();
        var consumer = mock(Consumer.class);
        var partition = new TopicPartition("delivery", 0);
        var token = tracker.beginAssignment(consumer);
        tracker.describeAssignment(consumer, token, Set.of(partition), Set.of(partition));
        tracker.confirmPositions(consumer, token, Map.of(partition, 7L));
    }

    private void assertNotReady(NotificationSseLifecycle lifecycle) {
        assertThat(lifecycle.isAcceptingSubscriptions()).isFalse();
        assertThatThrownBy(lifecycle::assertAcceptingSubscriptions)
            .isInstanceOfSatisfying(NotificationBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(NotificationErrorCode.NOTIFICATION_STREAM_NOT_READY));
    }

    private NotificationSseConnection connection(String id) {
        return new NotificationSseConnection(7L, id, new SseEmitter(5000L), LocalDateTime.now());
    }
    private static NotificationKafkaProperties kafkaProperties() {
        var properties = new NotificationKafkaProperties();
        properties.setDeliveryTopic("delivery");
        return properties;
    }

}
