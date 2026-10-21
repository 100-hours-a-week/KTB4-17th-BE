package com.team.dating_backend.notification.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.team.dating_backend.notification.alert.NotificationFailureReporter;
import com.team.dating_backend.notification.config.NotificationPublishConfiguration;
import com.team.dating_backend.notification.config.NotificationPublishProperties;
import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.enums.NotificationFailureCode;
import com.team.dating_backend.notification.enums.NotificationType;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationContext;

@Timeout(10)
class NotificationPublishDispatcherTest {

    private final NotificationRequestProducer producer = mock(NotificationRequestProducer.class);
    private final NotificationFailureReporter reporter = mock(NotificationFailureReporter.class);
    private final NotificationCreateRequested request = new NotificationCreateRequested(1, "LIKE_SENT:501", 7L,
        NotificationType.LIKE, "좋아요", "새로운 알림", null, null, Instant.parse("2026-10-20T01:02:03.123456789Z"));

    @Test
    void 호출_스레드에서는_작업만_제출하고_worker가_원본_요청을_발행한다() throws Exception {
        var executor = mock(Executor.class);
        var dispatcher = new NotificationPublishDispatcher(executor, producer, reporter);

        dispatcher.dispatch(request);

        var task = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(task.capture());
        verifyNoInteractions(producer, reporter);
        task.getValue().run();
        verify(producer).publishAndAwait(request);
        verifyNoMoreInteractions(producer);
        verifyNoInteractions(reporter);
    }

    @ParameterizedTest
    @MethodSource("publishFailures")
    void 발행_실패를_분류해_원본_요청과_예외를_보고하고_재시도하지_않는다(
        Exception failure, NotificationFailureCode code) throws Exception {
        doThrow(failure).when(producer).publishAndAwait(request);
        var dispatcher = new NotificationPublishDispatcher(Runnable::run, producer, reporter);

        dispatcher.dispatch(request);

        verify(reporter).report(code, request, failure);
        verify(producer).publishAndAwait(request);
        verifyNoMoreInteractions(producer, reporter);
    }

    @Test
    void interrupt_상태를_복원한_뒤_중단을_보고한다() throws Exception {
        var failure = new InterruptedException();
        doThrow(failure).when(producer).publishAndAwait(request);
        doAnswer(invocation -> {
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            return null;
        }).when(reporter).report(NotificationFailureCode.PUBLISH_INTERRUPTED, request, failure);

        try {
            new NotificationPublishDispatcher(Runnable::run, producer, reporter).dispatch(request);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        verify(reporter).report(NotificationFailureCode.PUBLISH_INTERRUPTED, request, failure);
        verify(producer).publishAndAwait(request);
        verifyNoMoreInteractions(producer, reporter);
    }

    @Test
    void 실제_pool과_대기열이_포화되면_호출_스레드에서_실행하지_않고_거절을_보고한다() throws Exception {
        var properties = new NotificationPublishProperties();
        properties.setPoolSize(1);
        properties.setQueueCapacity(1);
        var executor = new NotificationPublishConfiguration()
            .notificationPublishExecutor(properties, mock(ApplicationContext.class));
        executor.initialize();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var worker = new AtomicReference<Thread>();
        doAnswer(invocation -> {
            worker.set(Thread.currentThread());
            started.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(producer).publishAndAwait(request);
        var dispatcher = new NotificationPublishDispatcher(executor, producer, reporter);
        try {
            dispatcher.dispatch(request);
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            dispatcher.dispatch(request);
            dispatcher.dispatch(request);

            assertThat(worker.get()).isNotSameAs(Thread.currentThread());
            assertThat(worker.get().getName()).startsWith("notification-publish-");
            var rejection = ArgumentCaptor.forClass(RejectedExecutionException.class);
            verify(reporter).report(eq(NotificationFailureCode.PUBLISH_SUBMISSION_REJECTED),
                same(request), rejection.capture());
            verify(producer).publishAndAwait(request);
        } finally {
            release.countDown();
            executor.shutdown();
        }
        assertThat(executor.getThreadPoolExecutor().awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        verify(producer, times(2)).publishAndAwait(request);
        verifyNoMoreInteractions(reporter);
    }

    private static Stream<Arguments> publishFailures() {
        return Stream.of(
            Arguments.of(new IllegalStateException(), NotificationFailureCode.PUBLISH_CALL_FAILED),
            Arguments.of(new ExecutionException(new IllegalStateException()),
                NotificationFailureCode.PUBLISH_RESULT_FAILED),
            Arguments.of(new TimeoutException(), NotificationFailureCode.PUBLISH_RESULT_TIMEOUT));
    }
}
