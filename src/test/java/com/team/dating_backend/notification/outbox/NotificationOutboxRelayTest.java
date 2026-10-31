package com.team.dating_backend.notification.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.team.dating_backend.notification.alert.NotificationFailureReporter;
import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.enums.NotificationFailureCode;
import com.team.dating_backend.notification.enums.NotificationErrorCode;
import com.team.dating_backend.notification.service.NotificationOutboxFailureService;
import com.team.dating_backend.notification.service.NotificationOutboxFailureService.FailureResult;
import com.team.dating_backend.notification.service.NotificationCreateRequestedValidator;
import com.team.dating_backend.notification.entity.NotificationRequestOutbox;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.exception.NotificationInvalidCreateRequestException;
import com.team.dating_backend.notification.kafka.NotificationRequestProducer;
import com.team.dating_backend.notification.service.NotificationRequestOutboxService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class NotificationOutboxRelayTest {

    private static final Long OUTBOX_ID = 1L;
    private static final NotificationCreateRequested REQUEST = new NotificationCreateRequested(
        1, "LIKE_SENT:501", 7L, NotificationType.LIKE, "좋아요", "원본 요청", "PROFILE", 8L,
        Instant.parse("2026-10-20T01:02:03.123456789Z"));
    private static final RecordMetadata METADATA = new RecordMetadata(new TopicPartition("notifications", 0),
        0L, 0, 0L, 0, 0);

    @Mock
    private NotificationRequestOutboxService outboxService;
    @Mock
    private NotificationRequestProducer producer;
    @Mock
    private NotificationOutboxFailureService failureService;
    @Mock
    private NotificationFailureReporter failureReporter;
    @Spy
    private NotificationOutboxFailureClassifier failureClassifier = new NotificationOutboxFailureClassifier();
    private final NotificationRequestOutbox outbox = new NotificationRequestOutbox(REQUEST.sourceEventId(),
        REQUEST.recipientUserId(), REQUEST.type(),
        new NotificationRequestOutboxCodec(new NotificationCreateRequestedValidator()).encode(REQUEST),
        LocalDateTime.now(ZoneOffset.UTC));
    @InjectMocks
    private NotificationOutboxRelay relay;

    @AfterEach
    void cleanUp() {
        TransactionSynchronizationManager.clear();
        Thread.interrupted();
    }

    @Test
    void 저장된_요청을_복원해_발행하고_성공_결과_이후에만_완료를_기록한다() throws Exception {
        givenPendingRequest();
        given(producer.publishAndAwait(REQUEST)).willReturn(METADATA);

        assertThat(relay.publish(OUTBOX_ID)).isTrue();

        var order = inOrder(outboxService, producer);
        order.verify(outboxService).findDueById(eq(OUTBOX_ID), any(LocalDateTime.class));
        order.verify(outboxService).restore(outbox);
        order.verify(producer).publishAndAwait(REQUEST);
        order.verify(outboxService).markPublished(eq(OUTBOX_ID), any(LocalDateTime.class));
        verifyNoMoreInteractions(outboxService, producer);
    }

    @Test
    void 현재_발행_후보가_없으면_복원과_발행과_상태_갱신을_하지_않는다() throws Exception {
        given(outboxService.findDueById(eq(OUTBOX_ID), any(LocalDateTime.class))).willReturn(Optional.empty());

        assertThat(relay.publish(OUTBOX_ID)).isFalse();

        verify(outboxService).findDueById(eq(OUTBOX_ID), any(LocalDateTime.class));
        verifyNoMoreInteractions(outboxService);
        verifyNoInteractions(producer);
    }

    @ParameterizedTest
    @MethodSource("publishFailures")
    void 발행_실패는_에러코드와_원인으로_전달하고_즉시_재시도하지_않는다(Exception failure,
        NotificationFailureCode code) throws Exception {
        givenPendingRequest();
        if (!(failure instanceof InterruptedException)) {
            givenFailureRecorded(code, 1, false);
        }
        given(producer.publishAndAwait(REQUEST)).willThrow(failure);

        assertCodedFailure(code == NotificationFailureCode.PUBLISH_INTERRUPTED
            ? NotificationErrorCode.OUTBOX_PUBLISH_INTERRUPTED
            : NotificationErrorCode.OUTBOX_PUBLISH_FAILED, failure);

        verify(outboxService).findDueById(eq(OUTBOX_ID), any(LocalDateTime.class));
        verify(outboxService).restore(outbox);
        verify(producer).publishAndAwait(REQUEST);
        verifyNoMoreInteractions(outboxService, producer);
        verify(failureReporter).report(argThat(context -> context.errorCode() == code
            && context.sourceEventId().equals(REQUEST.sourceEventId())
            && context.recipientUserId().equals(REQUEST.recipientUserId())));
        if (failure instanceof InterruptedException) {
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verifyNoInteractions(failureService);
        } else {
            verify(failureService).recordFailure(eq(OUTBOX_ID), eq(code), any(LocalDateTime.class));
        }
    }

    @Test
    void 복원에_실패하면_Kafka_발행과_완료_기록을_하지_않는다() {
        given(outboxService.findDueById(eq(OUTBOX_ID), any(LocalDateTime.class))).willReturn(Optional.of(outbox));
        var failure = new NotificationInvalidCreateRequestException();
        given(outboxService.restore(outbox)).willThrow(failure);
        givenFailureRecorded(NotificationFailureCode.OUTBOX_RESTORE_FAILED, 1, false);

        assertCodedFailure(NotificationErrorCode.OUTBOX_RESTORE_FAILED, failure);

        verify(outboxService).findDueById(eq(OUTBOX_ID), any(LocalDateTime.class));
        verify(outboxService).restore(outbox);
        verifyNoMoreInteractions(outboxService);
        verifyNoInteractions(producer);
        verify(failureReporter)
            .report(argThat(context -> context.errorCode() == NotificationFailureCode.OUTBOX_RESTORE_FAILED
                && context.sourceEventId().equals(REQUEST.sourceEventId()) && context.requestedAt() == null));
    }

    @Test
    void 성공_결과가_없으면_완료를_기록하지_않는다() throws Exception {
        givenPendingRequest();
        givenFailureRecorded(NotificationFailureCode.PUBLISH_CALL_FAILED, 1, false);

        assertThatThrownBy(() -> relay.publish(OUTBOX_ID))
            .isInstanceOfSatisfying(NotificationBusinessException.class, failure -> {
                assertThat(failure.getErrorCode()).isEqualTo(NotificationErrorCode.OUTBOX_PUBLISH_FAILED);
                assertThat(failure.getMessage()).isNull();
            })
            .hasCauseInstanceOf(NotificationBusinessException.class);

        verify(outboxService).findDueById(eq(OUTBOX_ID), any(LocalDateTime.class));
        verify(outboxService).restore(outbox);
        verify(producer).publishAndAwait(REQUEST);
        verifyNoMoreInteractions(outboxService, producer);
    }

    @Test
    void Kafka_성공_후_DB_완료_기록이_실패하면_그_예외를_전달한다() throws Exception {
        givenPendingRequest();
        given(producer.publishAndAwait(REQUEST)).willReturn(METADATA);
        var failure = new DataAccessResourceFailureException("database unavailable");
        doThrow(failure).when(outboxService).markPublished(eq(OUTBOX_ID), any(LocalDateTime.class));

        assertCodedFailure(NotificationErrorCode.OUTBOX_STATE_UPDATE_FAILED, failure);

        verify(producer).publishAndAwait(REQUEST);
        verify(outboxService).markPublished(eq(OUTBOX_ID), any(LocalDateTime.class));
        verifyNoMoreInteractions(producer);
        verifyNoInteractions(failureService);
        verify(failureReporter)
            .report(argThat(context -> context.errorCode() == NotificationFailureCode.OUTBOX_STATE_UPDATE_FAILED
                && context.kafkaDetails().attempt() == null));
    }

    @Test
    void 프록시를_거치지_않아도_실제_트랜잭션이_있으면_발행을_거부한다() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatThrownBy(() -> relay.publish(OUTBOX_ID)).isInstanceOf(NotificationBusinessException.class);

        verifyNoInteractions(outboxService, producer);
    }

    private void givenPendingRequest() {
        given(outboxService.findDueById(eq(OUTBOX_ID), any(LocalDateTime.class))).willReturn(Optional.of(outbox));
        given(outboxService.restore(outbox)).willReturn(REQUEST);
    }

    @Test
    void 이미_중단된_스레드는_DB_조회와_발행을_시작하지_않는다() {
        Thread.currentThread().interrupt();

        assertCodedFailure(NotificationErrorCode.OUTBOX_PUBLISH_INTERRUPTED, null);

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        verifyNoInteractions(outboxService, producer, failureService, failureReporter);
    }

    private void givenFailureRecorded(NotificationFailureCode code, int count, boolean exhausted) {
        given(failureService.recordFailure(eq(OUTBOX_ID), eq(code), any(LocalDateTime.class)))
            .willReturn(new FailureResult(count, exhausted));
    }

    @Test
    void 한도_소진은_원래_실패와_소진_원인을_보고하고_추가_발행을_하지_않는다() throws Exception {
        givenPendingRequest();
        var failure = new TimeoutException();
        given(producer.publishAndAwait(REQUEST)).willThrow(failure);
        givenFailureRecorded(NotificationFailureCode.PUBLISH_RESULT_TIMEOUT, 5, true);

        assertCodedFailure(NotificationErrorCode.OUTBOX_PUBLISH_FAILED, failure);

        verify(failureReporter)
            .report(argThat(context -> context.errorCode() == NotificationFailureCode.PUBLISH_RESULT_TIMEOUT
                && context.kafkaDetails().attempt() == 5 && Boolean.FALSE.equals(context.kafkaDetails().retryable())));
        verify(failureReporter)
            .report(argThat(context -> context.errorCode() == NotificationFailureCode.OUTBOX_RETRY_LIMIT_EXCEEDED));
        verify(producer).publishAndAwait(REQUEST);
        verifyNoMoreInteractions(producer);
    }

    @Test
    void 실패_기록의_DB_오류도_보고하고_원래_발행_예외를_보존한다() throws Exception {
        givenPendingRequest();
        var failure = new TimeoutException();
        var recordingFailure = new DataAccessResourceFailureException("database unavailable");
        given(producer.publishAndAwait(REQUEST)).willThrow(failure);
        given(failureService.recordFailure(eq(OUTBOX_ID), eq(NotificationFailureCode.PUBLISH_RESULT_TIMEOUT),
            any(LocalDateTime.class)))
            .willThrow(recordingFailure);

        assertCodedFailure(NotificationErrorCode.OUTBOX_PUBLISH_FAILED, failure);

        assertThat(failure.getSuppressed()).containsExactly(recordingFailure);
        verify(failureReporter)
            .report(argThat(context -> context.errorCode() == NotificationFailureCode.PUBLISH_RESULT_TIMEOUT
                && context.kafkaDetails().retryable() == null));
        verify(failureReporter)
            .report(argThat(context -> context.errorCode() == NotificationFailureCode.OUTBOX_STATE_UPDATE_FAILED));
    }

    @Test
    void 실패_보고_예외가_원래_발행_예외를_덮어쓰지_않는다() throws Exception {
        givenPendingRequest();
        var failure = new TimeoutException();
        var reportingFailure = new IllegalStateException("report unavailable");
        given(producer.publishAndAwait(REQUEST)).willThrow(failure);
        givenFailureRecorded(NotificationFailureCode.PUBLISH_RESULT_TIMEOUT, 1, false);
        doThrow(reportingFailure).when(failureReporter)
            .report(any(com.team.dating_backend.notification.alert.NotificationFailureContext.class));

        assertCodedFailure(NotificationErrorCode.OUTBOX_PUBLISH_FAILED, failure);

        assertThat(failure.getSuppressed()).containsExactly(reportingFailure);
    }

    @Test
    void 조회_DB_오류도_에러코드와_원인으로_전달한다() {
        var failure = new DataAccessResourceFailureException("database unavailable");
        given(outboxService.findDueById(eq(OUTBOX_ID), any(LocalDateTime.class))).willThrow(failure);

        assertCodedFailure(NotificationErrorCode.OUTBOX_LOOKUP_FAILED, failure);

        verifyNoInteractions(producer, failureService, failureReporter);
    }

    private void assertCodedFailure(NotificationErrorCode code, Throwable cause) {
        assertThatThrownBy(() -> relay.publish(OUTBOX_ID))
            .isInstanceOfSatisfying(NotificationBusinessException.class, failure -> {
                assertThat(failure.getErrorCode()).isEqualTo(code);
                assertThat(failure.getMessage()).isNull();
                assertThat(failure.getCause()).isSameAs(cause);
            });
    }

    private static Stream<Arguments> publishFailures() {
        return Stream.of(
            Arguments.of(new ExecutionException(new IllegalStateException()),
                NotificationFailureCode.PUBLISH_RESULT_FAILED),
            Arguments.of(new TimeoutException(), NotificationFailureCode.PUBLISH_RESULT_TIMEOUT),
            Arguments.of(new InterruptedException(), NotificationFailureCode.PUBLISH_INTERRUPTED),
            Arguments.of(new IllegalStateException(), NotificationFailureCode.PUBLISH_CALL_FAILED));
    }
}
