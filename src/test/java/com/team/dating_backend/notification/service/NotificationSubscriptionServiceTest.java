package com.team.dating_backend.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;

import com.team.dating_backend.notification.config.NotificationSseProperties;
import com.team.dating_backend.notification.config.NotificationKafkaProperties;
import com.team.dating_backend.notification.config.NotificationDeliveryProperties;
import com.team.dating_backend.notification.sse.NotificationDeliveryReadinessTracker;
import com.team.dating_backend.notification.sse.NotificationSseLifecycle;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import com.team.dating_backend.notification.enums.NotificationErrorCode;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.sse.NotificationEmitterRegistry;
import com.team.dating_backend.notification.sse.NotificationSseConnection;
import com.team.dating_backend.notification.sse.NotificationSseSender;
import com.team.dating_backend.notification.sse.NotificationSseObservation;
import com.team.dating_backend.notification.enums.NotificationSseCloseReason;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class NotificationSubscriptionServiceTest {

    private NotificationAccessService accessService;
    private NotificationEmitterRegistry registry;
    private NotificationSseSender sender;
    private NotificationSseProperties properties;
    private NotificationSubscriptionService service;
    private NotificationSseLifecycle lifecycle;
    private NotificationSseObservation observation;

    @BeforeEach
    void setUp() {
        accessService = mock(NotificationAccessService.class);
        observation = mock(NotificationSseObservation.class);
        registry = new NotificationEmitterRegistry(observation);
        sender = mock(NotificationSseSender.class);
        properties = new NotificationSseProperties();
        lifecycle = lifecycle(registry);
        service = new NotificationSubscriptionService(accessService, registry, sender, properties, lifecycle,
            observation);
    }

    @Test
    void 비활성_회원은_연결을_등록하거나_초기_이벤트를_준비하지_않는다() {
        doThrow(new NotificationBusinessException(NotificationErrorCode.REQUESTER_NOT_ACTIVE))
            .when(accessService).assertActiveUser(1L);

        assertThatThrownBy(() -> service.subscribe(1L))
            .isInstanceOfSatisfying(NotificationBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(NotificationErrorCode.REQUESTER_NOT_ACTIVE));

        assertThat(registry.snapshotAll()).isEmpty();
        verifyNoInteractions(sender);
    }

    @Test
    void 수용_불가_요청은_emitter_생성_전에_거절한다() {
        lifecycle.beginDrain();
        try (MockedConstruction<SseEmitter> emitters = mockConstruction(SseEmitter.class)) {
            assertThatThrownBy(() -> service.subscribe(1L))
                .isInstanceOfSatisfying(NotificationBusinessException.class,
                    exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(NotificationErrorCode.NOTIFICATION_STREAM_NOT_READY));
            assertThat(emitters.constructed()).isEmpty();
            assertThat(registry.snapshotAll()).isEmpty();
            verify(accessService).assertActiveUser(1L);
            verifyNoInteractions(sender);
            verify(observation, never()).initializationFailed(any(), any());
        }
    }

    @Test
    void 최초_검사_이후_수용이_중단되면_새_연결만_정리하고_기존_연결을_보존한다() throws Exception {
        var existing = service.subscribe(1L);
        lifecycle = spy(lifecycle);
        service = new NotificationSubscriptionService(accessService, registry, sender, properties, lifecycle,
            observation);
        try (MockedConstruction<SseEmitter> emitters = mockConstruction(SseEmitter.class, (emitter, context) -> {
            doAnswer(invocation -> {
                lifecycle.beginDrain();
                return null;
            }).when(emitter).onCompletion(any());
        })) {
            assertThatThrownBy(() -> service.subscribe(1L))
                .isInstanceOfSatisfying(NotificationBusinessException.class,
                    exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(NotificationErrorCode.NOTIFICATION_STREAM_NOT_READY));
            var connection = ArgumentCaptor.forClass(NotificationSseConnection.class);
            verify(lifecycle).registerIfAccepting(connection.capture());
            assertThat(connection.getValue().isClosed()).isTrue();
            assertThat(registry.snapshotAll()).containsExactly(existing);
            assertThat(existing.isClosed()).isFalse();
            verify(sender, never()).sendConnected(connection.getValue());
            verify(observation, never()).initializationFailed(any(), any());
        }
    }

    @Test
    void 같은_회원의_새_구독은_기존_연결을_보존하고_각각_다른_연결을_생성한다() throws Exception {
        NotificationSseConnection first = service.subscribe(1L);
        NotificationSseConnection second = service.subscribe(1L);

        assertThat(first.getConnectionId()).isNotEqualTo(second.getConnectionId());
        assertThat(first.getEmitter()).isNotSameAs(second.getEmitter());
        assertThat(first.getEmitter().getTimeout()).isEqualTo(1_800_000L);
        assertThat(registry.findByUserId(1L)).containsExactlyInAnyOrder(first, second);
        verify(sender).sendConnected(first);
        verify(sender).sendConnected(second);
    }

    @Test
    void 초기_이벤트_준비에_실패하면_연결을_제거하고_실패를_전달한다() throws Exception {
        IOException failure = new IOException();
        doThrow(failure).when(sender).sendConnected(any());

        assertThatThrownBy(() -> service.subscribe(1L)).isSameAs(failure);

        ArgumentCaptor<NotificationSseConnection> connection = ArgumentCaptor.forClass(NotificationSseConnection.class);
        verify(sender).sendConnected(connection.capture());
        assertThat(connection.getValue().isClosed()).isTrue();
        assertThat(registry.snapshotAll()).isEmpty();
        verify(observation, never()).initializationFailed(any(), any());
        verify(observation).connectionClosed(eq(connection.getValue()),
            eq(NotificationSseCloseReason.TRANSPORT_ERROR), anyLong());
    }

    @Test
    void 타임아웃은_연결을_제거하고_완료를_요청하며_완료_콜백의_반복_정리를_허용한다() throws Exception {
        try (MockedConstruction<SseEmitter> emitters = mockConstruction(SseEmitter.class)) {
            NotificationSseConnection connection = service.subscribe(1L);
            SseEmitter emitter = emitters.constructed().getFirst();
            ArgumentCaptor<Runnable> timeout = ArgumentCaptor.forClass(Runnable.class);
            ArgumentCaptor<Runnable> completion = ArgumentCaptor.forClass(Runnable.class);
            verify(emitter).onTimeout(timeout.capture());
            verify(emitter).onCompletion(completion.capture());

            timeout.getValue().run();
            completion.getValue().run();

            assertThat(connection.isClosed()).isTrue();
            assertThat(registry.snapshotAll()).isEmpty();
            verify(emitter).complete();
            verify(observation).connectionClosed(eq(connection), eq(NotificationSseCloseReason.TIMEOUT), anyLong());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void 오류_콜백은_다른_연결을_보존하고_추가_오류_종료를_요청하지_않는다() throws Exception {
        try (MockedConstruction<SseEmitter> emitters = mockConstruction(SseEmitter.class)) {
            NotificationSseConnection failed = service.subscribe(1L);
            NotificationSseConnection healthy = service.subscribe(1L);
            SseEmitter emitter = emitters.constructed().getFirst();
            ArgumentCaptor<Consumer<Throwable>> error = ArgumentCaptor.forClass(Consumer.class);
            verify(emitter).onError(error.capture());

            error.getValue().accept(new IOException());

            assertThat(failed.isClosed()).isTrue();
            assertThat(registry.findByUserId(1L)).containsExactly(healthy);
            verify(emitter, never()).completeWithError(any());

            ArgumentCaptor<Runnable> completion = ArgumentCaptor.forClass(Runnable.class);
            verify(healthy.getEmitter()).onCompletion(completion.capture());
            completion.getValue().run();
            assertThat(registry.snapshotAll()).isEmpty();
        }
    }

    @Test
    void 등록_직후_전송하려는_다른_스레드는_connected_준비가_끝날_때까지_기다린다() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch attempted = new CountDownLatch(1);
        AtomicReference<Future<?>> contender = new AtomicReference<>();
        NotificationEmitterRegistry orderedRegistry = new NotificationEmitterRegistry(observation) {
            @Override
            public boolean register(NotificationSseConnection connection) {
                boolean registered = super.register(connection);
                contender.set(executor.submit(() -> {
                    attempted.countDown();
                    connection.lockForSend();
                    try {
                        sender.sendHeartbeat(connection);
                    } finally {
                        connection.unlockForSend();
                    }
                }));
                return registered;
            }
        };
        NotificationSseSender checkingSender = mock(NotificationSseSender.class);
        doAnswer(invocation -> {
            assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> contender.get().get(100, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);
            return null;
        }).when(checkingSender).sendConnected(any());
        NotificationSubscriptionService orderedService = new NotificationSubscriptionService(
            accessService, orderedRegistry, checkingSender, properties, lifecycle(orderedRegistry), observation);

        try {
            NotificationSseConnection connection = orderedService.subscribe(1L);
            contender.get().get(5, TimeUnit.SECONDS);
            verify(sender).sendHeartbeat(connection);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }
    @Test
    void 초기_서버_전송_실패는_원본을_전달하고_초기화_실패와_종료를_한번만_기록한다() throws Exception {
        var failure = new IllegalStateException(new IllegalArgumentException());
        service = new NotificationSubscriptionService(accessService, registry,
            new NotificationSseSender(registry, observation), properties, lifecycle, observation);
        try (var emitters = mockConstruction(SseEmitter.class,
            (emitter, context) -> doThrow(failure).when(emitter).send(any(SseEmitter.SseEventBuilder.class)))) {
            assertThatThrownBy(() -> service.subscribe(1L)).isSameAs(failure);
            var connection = ArgumentCaptor.forClass(NotificationSseConnection.class);
            verify(observation).initializationFailed(connection.capture(), eq(failure));
            verify(observation).connectionClosed(eq(connection.getValue()),
                eq(NotificationSseCloseReason.INITIALIZATION_FAILURE), anyLong());
            assertThat(registry.snapshotAll()).isEmpty();
            verify(emitters.constructed().getFirst(), never()).completeWithError(any());
        }
    }

    private NotificationSseLifecycle lifecycle(NotificationEmitterRegistry registry) {
        return new NotificationSseLifecycle(new NotificationDeliveryProperties(),
            new NotificationDeliveryReadinessTracker(kafkaProperties()), registry,
            new DefaultListableBeanFactory()
                .getBeanProvider(org.springframework.kafka.config.KafkaListenerEndpointRegistry.class));
    }

    private NotificationKafkaProperties kafkaProperties() {
        var properties = new NotificationKafkaProperties();
        properties.setDeliveryTopic("delivery");
        return properties;
    }

}
