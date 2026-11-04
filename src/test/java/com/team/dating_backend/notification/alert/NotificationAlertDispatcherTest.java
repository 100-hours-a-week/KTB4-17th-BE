package com.team.dating_backend.notification.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.team.dating_backend.notification.config.NotificationAlertConfiguration;
import com.team.dating_backend.notification.config.NotificationAlertProperties;
import com.team.dating_backend.notification.enums.NotificationFailureCode;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class NotificationAlertDispatcherTest {

    private final NotificationAlertProperties properties = new NotificationAlertProperties();
    private final MockClock clock = new MockClock();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
    private final DiscordWebhookClient client = mock(DiscordWebhookClient.class);
    private final NotificationFailureContext context = new NotificationFailureContext(
        NotificationFailureCode.PUBLISH_RESULT_TIMEOUT, "event-123", 7L, null, null, Instant.now(), null, null);

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
    }

    @AfterEach
    void tearDown() {
        registry.close();
    }

    @Test
    void 비활성화하면_작업을_제출하지_않는다() {
        properties.setEnabled(false);
        Executor executor = mock(Executor.class);

        new NotificationAlertDispatcher(executor, client, properties, registry).dispatch(context);

        verifyNoInteractions(executor, client);
    }

    @Test
    void 호출_스레드에서_전송하지_않고_executor에_작업을_넘긴다() {
        Executor executor = mock(Executor.class);
        var dispatcher = new NotificationAlertDispatcher(executor, client, properties, registry);

        dispatcher.dispatch(context);

        var task = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(task.capture());
        verifyNoInteractions(client);
        task.getValue().run();
        verify(client).send(context);
        assertThat(registry.get("notification.alert.sent").counter().count()).isEqualTo(1);
    }

    @Test
    void 최근_60초의_시도를_유지해_시간_경계에서도_상한을_지킨다() {
        var dispatcher = new NotificationAlertDispatcher(Runnable::run, client, properties, registry);
        for (int i = 0; i < 3; i++) {
            dispatcher.dispatch(context);
        }
        clock.addSeconds(30);
        for (int i = 0; i < 3; i++) {
            dispatcher.dispatch(context);
        }
        clock.add(29_999, TimeUnit.MILLISECONDS);
        dispatcher.dispatch(context);
        verify(client, times(5)).send(context);

        clock.add(1, TimeUnit.MILLISECONDS);
        for (int i = 0; i < 4; i++) {
            dispatcher.dispatch(context);
        }

        verify(client, times(8)).send(context);
        assertThat(registry.get("notification.alert.suppressed").counter().count()).isEqualTo(3);
    }

    @Test
    void 전송_실패도_횟수에_포함하고_예외_내용_노출과_재전송을_막는다(CapturedOutput output) {
        doThrow(new IllegalStateException("unsafe-detail", new IllegalArgumentException("unsafe-detail")))
            .when(client).send(any());
        var dispatcher = new NotificationAlertDispatcher(Runnable::run, client, properties, registry);

        for (int i = 0; i < 6; i++) {
            dispatcher.dispatch(context);
        }

        verify(client, times(5)).send(context);
        assertThat(registry.get("notification.alert.failures").tag("error_code", "ALERT_WEBHOOK_FAILED")
            .counter().count()).isEqualTo(5);
        assertThat(registry.get("notification.alert.sent").counter().count()).isZero();
        assertThat(output.getAll())
            .contains("errorCode=ALERT_WEBHOOK_FAILED", "originalErrorCode=PUBLISH_RESULT_TIMEOUT")
            .doesNotContain("unsafe-detail");
    }

    @Test
    void 동시_작업에서도_전송_시도는_상한을_넘지_않는다() throws InterruptedException {
        try (var executor = Executors.newFixedThreadPool(8)) {
            var dispatcher = new NotificationAlertDispatcher(executor, client, properties, registry);
            for (int i = 0; i < 100; i++) {
                dispatcher.dispatch(context);
            }
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        verify(client, times(5)).send(context);
        assertThat(registry.get("notification.alert.suppressed").counter().count()).isEqualTo(95);
    }

    @Test
    void 실제_executor가_포화되면_호출부로_예외를_전파하지_않고_거절을_기록한다() throws InterruptedException {
        properties.setQueueCapacity(1);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            assertThat(Thread.currentThread().getName()).startsWith("notification-alert-");
            started.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(client).send(any());
        var executor = new NotificationAlertConfiguration().notificationAlertExecutor(properties);
        executor.initialize();
        try {
            var dispatcher = new NotificationAlertDispatcher(executor, client, properties, registry);
            dispatcher.dispatch(context);
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            dispatcher.dispatch(context);
            dispatcher.dispatch(context);

            assertThat(registry.get("notification.alert.failures").tag("error_code", "ALERT_SUBMISSION_REJECTED")
                .counter().count()).isEqualTo(1);
        } finally {
            release.countDown();
            executor.shutdown();
        }
        assertThat(executor.getThreadPoolExecutor().awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        verify(client, times(2)).send(context);
    }
}
