package com.team.dating_backend.notification.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.team.dating_backend.notification.config.NotificationDeliveryProperties;
import com.team.dating_backend.notification.config.NotificationKafkaProperties;
import com.team.dating_backend.notification.config.NotificationSseProperties;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class NotificationSseShutdownCoordinatorTest {

    private final NotificationSseProperties properties = new NotificationSseProperties();
    private final NotificationEmitterRegistry registry = new NotificationEmitterRegistry(
        mock(NotificationSseObservation.class));
    private final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    private final KafkaListenerEndpointRegistry endpoints = mock(KafkaListenerEndpointRegistry.class);
    private final MessageListenerContainer container = mock(MessageListenerContainer.class);
    private final ApplicationContext context = mock(ApplicationContext.class);
    private final Logger logger = (Logger) LoggerFactory.getLogger(NotificationSseShutdownCoordinator.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private NotificationSseLifecycle lifecycle;
    private NotificationSseShutdownCoordinator coordinator;

    @BeforeEach
    void setUp() {
        properties.setDrainTimeoutMs(0);
        properties.setCloseTimeoutMs(100);
        properties.setConsumerStopTimeoutMs(5000);
        var kafka = new NotificationKafkaProperties();
        kafka.setDeliveryTopic("delivery");
        lifecycle = spy(new NotificationSseLifecycle(new NotificationDeliveryProperties(),
            new NotificationDeliveryReadinessTracker(kafka), registry,
            beans.getBeanProvider(KafkaListenerEndpointRegistry.class)));
        coordinator = new NotificationSseShutdownCoordinator(properties, lifecycle, registry,
            beans.getBeanProvider(KafkaListenerEndpointRegistry.class),
            new NotificationSseProcessIdentity(new NotificationDeliveryProperties()), context);
        beans.registerSingleton("endpoints", endpoints);
        when(endpoints.getListenerContainer("notificationDeliveryConsumer")).thenReturn(container);
        when(container.isRunning()).thenReturn(true);
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(container).stop(any(Runnable.class));
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        logs.stop();
    }

    @Test
    void 자기_Context에서_연결_정리_후_Consumer_종료를_확인하고_중복_종료는_한번만_처리한다() throws Exception {
        var first = connection("first");
        var second = connection("second");
        var stopping = new CountDownLatch(1);
        var callback = new AtomicReference<Runnable>();
        doAnswer(invocation -> {
            assertThat(lifecycle.isAcceptingSubscriptions()).isFalse();
            assertThat(registry.snapshotAll()).isEmpty();
            verify(first.getEmitter()).complete();
            verify(second.getEmitter()).complete();
            callback.set(invocation.getArgument(0));
            stopping.countDown();
            return null;
        }).when(container).stop(any(Runnable.class));
        coordinator.onApplicationEvent(new ContextClosedEvent(mock(ApplicationContext.class)));
        verify(lifecycle, never()).beginDrain();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var shutdown = executor.submit(() -> coordinator.onApplicationEvent(new ContextClosedEvent(context)));
            try {
                assertThat(stopping.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(shutdown.isDone()).isFalse();
                var duplicate = executor.submit(coordinator::drainAndStop);
                callback.get().run();
                shutdown.get(5, TimeUnit.SECONDS);
                duplicate.get(5, TimeUnit.SECONDS);
            } finally {
                if (callback.get() != null) {
                    callback.get().run();
                }
            }
        }
        verify(lifecycle).beginDrain();
        verify(container).stop(any(Runnable.class));
        assertThat(finishedLog()).contains("consumerStopResult=STOPPED", "completeRequests=2", "incomplete=0");
    }

    @Test
    void 전체_연결이_잠금_예산을_공유하고_시간이_끝난_연결에는_complete를_강행하지_않는다() throws Exception {
        var first = spy(new NotificationSseConnection(7L, "first", mock(SseEmitter.class), LocalDateTime.now()));
        var second = spy(new NotificationSseConnection(7L, "second", mock(SseEmitter.class), LocalDateTime.now()));
        registry.register(first);
        registry.register(second);
        var waits = new ArrayList<Long>();
        for (var connection : List.of(first, second)) {
            doAnswer(invocation -> {
                waits.add(invocation.getArgument(0));
                return invocation.callRealMethod();
            }).when(connection).tryLockForSend(anyLong());
        }
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var owner = executor.submit(() -> {
                first.lockForSend();
                second.lockForSend();
                try {
                    locked.countDown();
                    assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                } finally {
                    second.unlockForSend();
                    first.unlockForSend();
                }
                return null;
            });
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                coordinator.drainAndStop();
                assertThat(waits).hasSize(2);
                assertThat(waits.getFirst()).isBetween(1L, TimeUnit.MILLISECONDS.toNanos(100));
                assertThat(waits.getLast()).isZero();
                assertThat(registry.snapshotAll()).isEmpty();
                assertThat(first.isClosed()).isTrue();
                assertThat(second.isClosed()).isTrue();
                verify(first.getEmitter(), never()).complete();
                verify(second.getEmitter(), never()).complete();
                assertThat(finishedLog()).contains("incomplete=2", "completeRequests=0");
            } finally {
                release.countDown();
            }
            owner.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void 한_연결의_complete_실패에도_다른_연결과_Consumer를_정리한다() {
        var failed = connection("failed");
        var healthy = connection("healthy");
        doThrow(new IllegalStateException()).when(failed.getEmitter()).complete();

        coordinator.drainAndStop();

        verify(healthy.getEmitter()).complete();
        verify(container).stop(any(Runnable.class));
        assertThat(registry.snapshotAll()).isEmpty();
        assertThat(finishedLog()).contains("failed=1", "completeRequests=1", "consumerStopResult=STOPPED");
    }

    @Test
    void 연결_정리_Error를_보존하고_finally의_Consumer_종료_Error도_유실하지_않는다() {
        var connection = connection("fatal");
        var original = new AssertionError();
        var stopFailure = new AssertionError();
        doThrow(original).when(connection.getEmitter()).complete();
        doThrow(stopFailure).when(container).stop(any(Runnable.class));

        assertThatThrownBy(coordinator::drainAndStop).isSameAs(original);
        assertThat(original.getSuppressed()).containsExactly(stopFailure);
        assertThat(registry.snapshotAll()).isEmpty();
        verify(container).stop(any(Runnable.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void interrupt는_대기를_줄여도_연결과_Consumer_정리를_요청하고_flag를_복원한다(boolean initiallyInterrupted)
        throws Exception {
        properties.setDrainTimeoutMs(10_000);
        var connection = connection("interrupted");
        var entered = new CountDownLatch(1);
        var worker = new AtomicReference<Thread>();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            entered.countDown();
            return null;
        }).when(lifecycle).beginDrain();
        doAnswer(invocation -> null).when(container).stop(any(Runnable.class));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var shutdown = executor.submit(() -> {
                worker.set(Thread.currentThread());
                if (initiallyInterrupted) {
                    Thread.currentThread().interrupt();
                }
                coordinator.drainAndStop();
                return Thread.currentThread().isInterrupted();
            });
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            if (!initiallyInterrupted) {
                worker.get().interrupt();
            }
            assertThat(shutdown.get(3, TimeUnit.SECONDS)).isTrue();
        }
        verify(connection.getEmitter()).complete();
        verify(container).stop(any(Runnable.class));
        assertThat(registry.snapshotAll()).isEmpty();
        assertThat(finishedLog()).contains("consumerStopResult=INTERRUPTED", "interrupted=true");
    }

    @Test
    void Consumer_callback_시간초과는_종료_완료와_구분하고_반환한다() throws Exception {
        properties.setConsumerStopTimeoutMs(30);
        connection("timeout");
        doAnswer(invocation -> null).when(container).stop(any(Runnable.class));
        try (var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(coordinator::drainAndStop).get(3, TimeUnit.SECONDS);
        }
        assertThat(finishedLog()).contains("consumerStopResult=TIMED_OUT");
        assertThat(registry.snapshotAll()).isEmpty();
    }

    @Test
    void delivery_Container가_없어도_기존_SSE를_정리한다() {
        var connection = connection("legacy");
        beans.destroySingletons();
        coordinator.drainAndStop();
        verify(connection.getEmitter()).complete();
        verify(container, never()).stop(any(Runnable.class));
        assertThat(lifecycle.isAcceptingSubscriptions()).isFalse();
        assertThat(registry.snapshotAll()).isEmpty();
        assertThat(finishedLog()).contains("consumerStopResult=NOT_REGISTERED");
    }

    private NotificationSseConnection connection(String id) {
        var connection = new NotificationSseConnection(7L, id, mock(SseEmitter.class), LocalDateTime.now());
        registry.register(connection);
        return connection;
    }

    private String finishedLog() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage)
            .filter(message -> message.startsWith("SSE shutdown finished.")).findFirst().orElseThrow();
    }
}
