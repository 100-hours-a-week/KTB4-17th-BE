package com.team.dating_backend.notification.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.team.dating_backend.notification.config.NotificationAlertProperties;
import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.enums.NotificationFailureCode;
import com.team.dating_backend.notification.enums.NotificationType;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class NotificationFailureReporterTest {

    private final MockClock clock = new MockClock();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
    private final NotificationAlertDispatcher dispatcher = mock(NotificationAlertDispatcher.class);
    private final NotificationFailureReporter reporter = new NotificationFailureReporter(dispatcher, registry);

    @AfterEach
    void tearDown() {
        registry.close();
    }

    @Test
    void 원본_정보를_유지하고_민감한_내용_없이_통보보다_먼저_기록한다(CapturedOutput output) {
        Instant requestedAt = Instant.parse("2026-10-20T01:00:00Z");
        var request = new NotificationCreateRequested(1, "event-123", 7L, NotificationType.LIKE,
            "private-title", "private-body", null, null, requestedAt);
        doAnswer(invocation -> {
            assertThat(registry.get("notification.failures").tag("error_code", "PUBLISH_RESULT_TIMEOUT")
                .counter().count()).isEqualTo(1);
            assertThat(output.getAll()).contains("errorCode=PUBLISH_RESULT_TIMEOUT", "sourceEventId=event-123")
                .doesNotContain("private-title", "private-body", "private-exception", "private-cause");
            return null;
        }).when(dispatcher).dispatch(any());

        reporter.report(NotificationFailureCode.PUBLISH_RESULT_TIMEOUT, request,
            new ExecutionException("private-exception", new TimeoutException("private-cause")));

        var context = ArgumentCaptor.forClass(NotificationFailureContext.class);
        verify(dispatcher).dispatch(context.capture());
        assertThat(context.getValue()).isEqualTo(new NotificationFailureContext(
            NotificationFailureCode.PUBLISH_RESULT_TIMEOUT, "event-123", 7L, NotificationType.LIKE,
            requestedAt, Instant.ofEpochMilli(clock.wallTime()), "ExecutionException", "TimeoutException"));
        assertThat(registry.get("notification.failures").counter().getId().getTags())
            .hasSize(1).allSatisfy(tag -> assertThat(tag.getKey()).isEqualTo("error_code"));
    }

    @Test
    void 요청과_예외가_없어도_실패를_보고한다() {
        reporter.report(NotificationFailureCode.PUBLISH_CALL_FAILED, null, null);

        var context = ArgumentCaptor.forClass(NotificationFailureContext.class);
        verify(dispatcher).dispatch(context.capture());
        assertThat(context.getValue().sourceEventId()).isNull();
        assertThat(context.getValue().exceptionType()).isNull();
        assertThat(registry.get("notification.failures").counter().count()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = NotificationFailureCode.class, names = {"ALERT_SUBMISSION_REJECTED", "ALERT_WEBHOOK_FAILED"})
    void 통보_자체의_실패는_로컬에만_기록한다(NotificationFailureCode errorCode) {
        reporter.report(errorCode, null, null);

        verifyNoInteractions(dispatcher);
        assertThat(registry.get("notification.failures").tag("error_code", errorCode.name()).counter().count())
            .isEqualTo(1);
    }

    @Test
    void 빈도_제한으로_통보가_생략돼도_원본_실패는_모두_기록한다() {
        var properties = new NotificationAlertProperties();
        properties.setEnabled(true);
        var client = mock(DiscordWebhookClient.class);
        var limitedDispatcher = new NotificationAlertDispatcher(Runnable::run, client, properties, registry);
        var limitedReporter = new NotificationFailureReporter(limitedDispatcher, registry);

        for (int i = 0; i < 6; i++) {
            limitedReporter.report(NotificationFailureCode.PUBLISH_CALL_FAILED, null, null);
        }

        verify(client, times(5)).send(any());
        assertThat(registry.get("notification.failures").counter().count()).isEqualTo(6);
        assertThat(registry.get("notification.alert.suppressed").counter().count()).isEqualTo(1);
    }
}
