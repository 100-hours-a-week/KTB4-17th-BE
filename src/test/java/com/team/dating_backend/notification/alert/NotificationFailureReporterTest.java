package com.team.dating_backend.notification.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.team.dating_backend.notification.config.NotificationAlertProperties;
import com.team.dating_backend.notification.alert.NotificationFailureContext.KafkaFailureDetails;
import com.team.dating_backend.notification.alert.NotificationFailureContext.KafkaLocation;
import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.entity.NotificationRequestOutbox;
import com.team.dating_backend.notification.enums.NotificationFailureCode;
import com.team.dating_backend.notification.enums.NotificationOutboxStatus;
import com.team.dating_backend.notification.enums.NotificationType;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.LocalDateTime;
import com.team.dating_backend.notification.dto.event.NotificationDeliveryEvent;
import com.team.dating_backend.notification.dto.response.NotificationEventResponse;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;

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
        var request = new NotificationCreateRequested("event-123", 7L, NotificationType.LIKE,
            "private-title", "private-body", null, null, requestedAt);
        doAnswer(invocation -> {
            assertThat(registry.get("notification.failures").tag("error_code", "OUTBOX_DELIVERY_FAILED")
                .counter().count()).isEqualTo(1);
            assertThat(output.getAll()).contains("errorCode=OUTBOX_DELIVERY_FAILED", "sourceEventId=event-123")
                .doesNotContain("private-title", "private-body", "private-exception", "private-cause");
            return null;
        }).when(dispatcher).dispatch(any());

        reporter.report(NotificationFailureCode.OUTBOX_DELIVERY_FAILED, request,
            new ExecutionException("private-exception", new TimeoutException("private-cause")));

        var context = ArgumentCaptor.forClass(NotificationFailureContext.class);
        verify(dispatcher).dispatch(context.capture());
        assertThat(context.getValue()).isEqualTo(new NotificationFailureContext(
            NotificationFailureCode.OUTBOX_DELIVERY_FAILED, "event-123", 7L, NotificationType.LIKE,
            requestedAt, Instant.ofEpochMilli(clock.wallTime()), "ExecutionException", "TimeoutException"));
        assertThat(registry.get("notification.failures").counter().getId().getTags())
            .hasSize(1).allSatisfy(tag -> assertThat(tag.getKey()).isEqualTo("error_code"));
    }

    @Test
    void delivery_문맥은_로그와_통보에_유지하고_지표에는_errorCode만_사용한다(CapturedOutput output) {
        var event = new NotificationDeliveryEvent("source", 7L,
            new NotificationEventResponse(31L, "UNKNOWN", "private-title", "private-body", null, null, null,
                LocalDateTime.of(2026, 10, 23, 12, 0)));
        var context = NotificationFailureContext.fromDelivery(NotificationFailureCode.DELIVERY_INVALID_EVENT,
            event, null, Instant.now(), null);

        reporter.report(context);

        verify(dispatcher).dispatch(context);
        assertThat(output.getAll())
            .contains("failureStage=DELIVERY_CONSUMPTION", "notificationId=31",
                "notificationCreatedAt=2026-10-23T12:00", "requestedAt=null", "type=null")
            .doesNotContain("private-title", "private-body");
        var counter = registry.get("notification.failures").tag("error_code", "DELIVERY_INVALID_EVENT").counter();
        assertThat(counter.count()).isEqualTo(1);
        assertThat(counter.getId().getTags()).hasSize(1);
    }

    @Test
    void 요청과_예외가_없어도_실패를_보고한다() {
        reporter.report(NotificationFailureCode.OUTBOX_DELIVERY_FAILED, null, null);

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
    void Kafka_문맥을_기록하고_Delivery_통보에도_같은_문맥을_전달한다(CapturedOutput output) {
        var details = KafkaFailureDetails.from("retry-group", new KafkaLocation("create", 0, 4L),
            new KafkaLocation("create-retry-1", 1, 8L), new KafkaLocation("create-dlt", 1, 99L),
            3, true, new TimeoutException("private-processing"));
        var context = NotificationFailureContext.from(NotificationFailureCode.DELIVERY_INVALID_EVENT, null,
            new TimeoutException("private-exception"), Instant.now(), details);
        reporter.report(context);
        verify(dispatcher).dispatch(context);
        assertThat(output.getAll()).contains("failureStage=DELIVERY_CONSUMPTION", "consumerGroup=retry-group",
            "originalLocation=create:0:4", "inputLocation=create-retry-1:1:8", "destinationLocation=create-dlt:1:99",
            "attempt=3", "retryable=true", "processingExceptionType=TimeoutException")
            .doesNotContain("private-processing", "private-exception");
    }

    @ParameterizedTest
    @EnumSource(value = NotificationFailureCode.class, names = {"OUTBOX_CLAIM_FAILED", "OUTBOX_CLAIM_LOST"})
    void 점유_실패는_지표에_기록하고_Discord를_제출하지_않는다(NotificationFailureCode code) {
        reporter.report(code, null, new TimeoutException());
        verifyNoInteractions(dispatcher);
        assertThat(registry.get("notification.failures").tag("error_code", code.name()).counter().count()).isEqualTo(1);
    }

    @Test
    void 지표_실패에도_통보를_시도하고_통보_제출_예외를_전파하지_않는다() {
        var failingRegistry = spy(registry);
        doThrow(new IllegalStateException()).when(failingRegistry)
            .counter("notification.failures", "error_code", "DELIVERY_INVALID_EVENT");
        doThrow(new IllegalStateException()).when(dispatcher).dispatch(any());
        var context = NotificationFailureContext.from(NotificationFailureCode.DELIVERY_INVALID_EVENT, null, null,
            Instant.now());
        assertThatCode(() -> new NotificationFailureReporter(dispatcher, failingRegistry).report(context))
            .doesNotThrowAnyException();
        verify(dispatcher).dispatch(context);
        assertThat(registry.get("notification.reporting.failures").tag("operation", "METRIC").counter().count())
            .isEqualTo(1);
        assertThat(registry.get("notification.reporting.failures").tag("operation", "ALERT_DISPATCH").counter().count())
            .isEqualTo(1);
    }

    @Test
    void Delivery_통보의_거절과_웹훅_실패는_기록하고_전파하지_않는다() {
        var properties = new NotificationAlertProperties();
        properties.setEnabled(true);
        var client = mock(DiscordWebhookClient.class);
        var rejected = new NotificationAlertDispatcher(task -> {
            throw new java.util.concurrent.RejectedExecutionException();
        },
            client, properties, registry);
        var context = NotificationFailureContext.from(NotificationFailureCode.DELIVERY_INVALID_EVENT, null, null,
            Instant.now());
        assertThatCode(() -> new NotificationFailureReporter(rejected, registry).report(context))
            .doesNotThrowAnyException();
        verifyNoInteractions(client);
        doThrow(new IllegalStateException()).when(client).send(any());
        var failed = new NotificationAlertDispatcher(Runnable::run, client, properties, registry);
        assertThatCode(() -> new NotificationFailureReporter(failed, registry).report(context))
            .doesNotThrowAnyException();
        assertThat(registry.get("notification.failures").tag("error_code", "DELIVERY_INVALID_EVENT").counter().count())
            .isEqualTo(2);
        assertThat(registry.get("notification.alert.failures").tag("error_code", "ALERT_SUBMISSION_REJECTED").counter()
            .count()).isEqualTo(1);
        assertThat(
            registry.get("notification.alert.failures").tag("error_code", "ALERT_WEBHOOK_FAILED").counter().count())
            .isEqualTo(1);
    }

    @Test
    void 보고_실패_진단마저_실패해도_통보_제출을_시도한다() {
        var failingRegistry = mock(io.micrometer.core.instrument.MeterRegistry.class);
        org.mockito.Mockito.when(failingRegistry.counter(any(String.class), any(String[].class)))
            .thenThrow(new IllegalStateException("private-metric"));
        var context = NotificationFailureContext.from(NotificationFailureCode.DELIVERY_INVALID_EVENT, null, null,
            Instant.now());
        assertThatCode(() -> new NotificationFailureReporter(dispatcher, failingRegistry).report(context))
            .doesNotThrowAnyException();
        verify(dispatcher).dispatch(context);
    }

    @ParameterizedTest
    @CsvSource(
        {
            "OUTBOX_RESTORE_FAILED,PENDING", "OUTBOX_RESTORE_FAILED,FAILED",
            "OUTBOX_CREATION_FAILED,PENDING", "OUTBOX_CREATION_FAILED,FAILED",
            "OUTBOX_DELIVERY_FAILED,PENDING", "OUTBOX_DELIVERY_FAILED,FAILED",
            "OUTBOX_STATE_UPDATE_FAILED,PENDING", "OUTBOX_STATE_UPDATE_FAILED,FAILED"
        }
    )
    void Worker_실패는_단계와_DB_상태로_관측하고_FAILED만_통보한다(
        NotificationFailureCode code, NotificationOutboxStatus status, CapturedOutput output) {
        var outbox = new NotificationRequestOutbox("worker-source", 7L, NotificationType.LIKE,
            "private-payload", LocalDateTime.now());
        ReflectionTestUtils.setField(outbox, "id", 31L);
        var context = NotificationFailureContext.fromWorker(code, outbox, null,
            new ExecutionException("private-message", new TimeoutException("private-cause")),
            Instant.now(), 2, status, true);

        reporter.report(context);

        if (status == NotificationOutboxStatus.FAILED) {
            verify(dispatcher).dispatch(context);
        } else {
            verifyNoInteractions(dispatcher);
        }
        assertThat(output.getAll()).contains("outboxId=31", "sourceEventId=worker-source", "recipientUserId=7",
            "failureCount=2", "outboxStatus=" + status, "exceptionType=ExecutionException",
            "causeType=TimeoutException")
            .doesNotContain("private-payload", "private-message", "private-cause");
        var counter = registry.get("notification.outbox.worker.failures")
            .tags("error_code", code.name(), "failure_stage", context.failureStage().name(), "status", status.name())
            .counter();
        assertThat(counter.count()).isEqualTo(1);
        assertThat(counter.getId().getTags()).extracting(io.micrometer.core.instrument.Tag::getKey)
            .containsExactlyInAnyOrder("error_code", "failure_stage", "status");
    }

    @Test
    void Worker_실패_저장_결과가_불명확하면_횟수와_상태를_추정하거나_통보하지_않는다(CapturedOutput output) {
        var outbox = new NotificationRequestOutbox("worker-source", 7L, NotificationType.LIKE, "{}",
            LocalDateTime.now());
        var context = NotificationFailureContext.fromWorker(NotificationFailureCode.OUTBOX_STATE_UPDATE_FAILED,
            outbox, null, new IllegalStateException(), Instant.now(), null, null, null);

        reporter.report(context);

        verifyNoInteractions(dispatcher);
        assertThat(output.getAll()).contains("failureCount=null", "outboxStatus=null");
        assertThat(registry.get("notification.outbox.worker.failures").tag("status", "UNKNOWN").counter().count())
            .isEqualTo(1);
    }

    @Test
    void Worker_지표_실패에도_최종_실패_통보를_시도한다() {
        var outbox = new NotificationRequestOutbox("worker-source", 7L, NotificationType.LIKE, "{}",
            LocalDateTime.now());
        var context = NotificationFailureContext.fromWorker(NotificationFailureCode.OUTBOX_RESTORE_FAILED,
            outbox, null, null, Instant.now(), 1, NotificationOutboxStatus.FAILED, false);
        var failingRegistry = spy(registry);
        doThrow(new IllegalStateException()).when(failingRegistry).counter("notification.outbox.worker.failures",
            "error_code", "OUTBOX_RESTORE_FAILED", "failure_stage", "OUTBOX_RESTORE", "status", "FAILED");

        assertThatCode(() -> new NotificationFailureReporter(dispatcher, failingRegistry).report(context))
            .doesNotThrowAnyException();

        verify(dispatcher).dispatch(context);
        assertThat(registry.get("notification.reporting.failures").tag("operation", "OUTBOX_METRIC")
            .counter().count()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = NotificationFailureCode.class, names = {"OUTBOX_DELIVERY_FAILED", "DELIVERY_INVALID_EVENT"})
    void 빈도_제한으로_통보가_생략돼도_원본_실패는_모두_기록한다(NotificationFailureCode code) {
        var properties = new NotificationAlertProperties();
        properties.setEnabled(true);
        var client = mock(DiscordWebhookClient.class);
        var limitedDispatcher = new NotificationAlertDispatcher(Runnable::run, client, properties, registry);
        var limitedReporter = new NotificationFailureReporter(limitedDispatcher, registry);

        for (int i = 0; i < 6; i++) {
            limitedReporter.report(code, null, null);
        }

        verify(client, times(5)).send(any());
        assertThat(registry.get("notification.failures").counter().count()).isEqualTo(6);
        assertThat(registry.get("notification.alert.suppressed").counter().count()).isEqualTo(1);
    }
}
