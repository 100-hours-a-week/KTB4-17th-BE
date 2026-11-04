package com.team.dating_backend.notification.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.team.dating_backend.notification.alert.NotificationFailureReporter;
import com.team.dating_backend.notification.config.NotificationOutboxRelayProperties;
import com.team.dating_backend.notification.entity.NotificationRequestOutbox;
import com.team.dating_backend.notification.enums.NotificationErrorCode;
import com.team.dating_backend.notification.enums.NotificationFailureCode;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.service.NotificationRequestOutboxService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class NotificationOutboxPublishJobTest {

    private final NotificationRequestOutboxService outboxService = mock(NotificationRequestOutboxService.class);
    private final NotificationOutboxRelay relay = mock(NotificationOutboxRelay.class);
    private final NotificationFailureReporter reporter = mock(NotificationFailureReporter.class);
    private final NotificationOutboxRelayProperties properties = new NotificationOutboxRelayProperties();
    private final NotificationOutboxPublishJob job = new NotificationOutboxPublishJob(outboxService, relay,
        properties, reporter);

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        properties.setBatchSize(2);
    }

    @AfterEach
    void cleanUp() {
        Thread.interrupted();
        TransactionSynchronizationManager.clear();
    }

    @Test
    void 비활성화와_이미_중단된_실행은_조회하지_않는다() {
        properties.setEnabled(false);
        job.publishDueRequests();
        properties.setEnabled(true);
        Thread.currentThread().interrupt();

        job.publishDueRequests();

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        verifyNoInteractions(outboxService, relay, reporter);
    }

    @Test
    void 업무_트랜잭션에서는_조회와_발행을_시작하지_않는다() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatThrownBy(job::publishDueRequests).isInstanceOf(NotificationBusinessException.class);

        verifyNoInteractions(outboxService, relay, reporter);
    }

    @Test
    void 설정한_개수로_UTC_후보를_조회하고_반환_순서대로_한번씩_처리한다() {
        given(outboxService.findAvailable(2)).willReturn(List.of(outbox(1L), outbox(2L)));
        given(relay.publish(1L)).willReturn(false);
        given(relay.publish(2L)).willReturn(true);

        job.publishDueRequests();

        verify(outboxService).findAvailable(2);
        var order = inOrder(relay);
        order.verify(relay).publish(1L);
        order.verify(relay).publish(2L);
        verifyNoInteractions(reporter);
    }

    @Test
    void 후보가_없으면_발행하지_않는다() {
        given(outboxService.findAvailable(2)).willReturn(List.of());

        job.publishDueRequests();

        verifyNoInteractions(relay, reporter);
    }

    @ParameterizedTest
    @EnumSource(
        value = NotificationErrorCode.class,
        names = {"OUTBOX_RESTORE_FAILED", "OUTBOX_PUBLISH_FAILED", "OUTBOX_STATE_UPDATE_FAILED"}
    )
    void Relay에서_기록하고_보고한_실패는_중복_보고없이_다음_후보를_처리한다(NotificationErrorCode code) {
        given(outboxService.findAvailable(2)).willReturn(List.of(outbox(1L), outbox(2L)));
        given(relay.publish(1L)).willThrow(new NotificationBusinessException(code));

        assertThatCode(job::publishDueRequests).doesNotThrowAnyException();

        verify(relay).publish(2L);
        verifyNoInteractions(reporter);
    }

    @Test
    void 개별_조회_오류는_후보의_식별정보로_보고하고_나머지를_처리한다() {
        var first = outbox(1L);
        given(outboxService.findAvailable(2)).willReturn(List.of(first, outbox(2L)));
        given(relay.publish(1L)).willThrow(
            new NotificationBusinessException(NotificationErrorCode.OUTBOX_LOOKUP_FAILED));

        job.publishDueRequests();

        verify(relay).publish(2L);
        verify(reporter).report(argThat(context -> context.errorCode() == NotificationFailureCode.OUTBOX_LOOKUP_FAILED
            && context.sourceEventId().equals(first.getSourceEventId()) && context.recipientUserId().equals(7L)
            && context.kafkaDetails().attempt() == null && context.kafkaDetails().retryable() == null), eq(false));
    }

    @Test
    void 예상하지_못한_오류와_보고_실패도_다음_후보의_발행을_막지_않는다() {
        var failure = new IllegalStateException();
        var reportingFailure = new IllegalArgumentException();
        given(outboxService.findAvailable(2)).willReturn(List.of(outbox(1L), outbox(2L)));
        given(relay.publish(1L)).willThrow(failure);
        doThrow(reportingFailure).when(reporter).report(any(), eq(false));

        assertThatCode(job::publishDueRequests).doesNotThrowAnyException();

        verify(relay).publish(2L);
        verify(reporter).report(argThat(context -> context.errorCode() == NotificationFailureCode.OUTBOX_RELAY_FAILED),
            eq(false));
        assertThat(failure.getSuppressed()).containsExactly(reportingFailure);
    }

    @Test
    void 전체_조회_오류는_발행없이_보고하고_다음_실행에서_다시_조회한다() {
        given(outboxService.findAvailable(2))
            .willThrow(new DataAccessResourceFailureException("private-message"))
            .willReturn(List.of(outbox(2L)));

        assertThatCode(job::publishDueRequests).doesNotThrowAnyException();
        verifyNoInteractions(relay);
        verify(reporter).report(argThat(context -> context.errorCode() == NotificationFailureCode.OUTBOX_LOOKUP_FAILED
            && context.sourceEventId() == null && context.recipientUserId() == null), eq(false));

        job.publishDueRequests();

        verify(outboxService, times(2)).findAvailable(2);
        verify(relay).publish(2L);
    }

    @Test
    void 전체_조회_오류의_보고까지_실패해도_다음_실행을_막지_않는다() {
        var failure = new DataAccessResourceFailureException("private-message");
        var reportingFailure = new IllegalStateException();
        given(outboxService.findAvailable(2)).willThrow(failure).willReturn(List.of(outbox(1L)));
        doThrow(reportingFailure).when(reporter).report(any(), eq(false));

        job.publishDueRequests();
        job.publishDueRequests();

        assertThat(failure.getSuppressed()).containsExactly(reportingFailure);
        verify(relay).publish(1L);
    }

    @Test
    void 중단_에러코드는_플래그를_유지하고_나머지_후보를_처리하지_않는다() {
        given(outboxService.findAvailable(2)).willReturn(List.of(outbox(1L), outbox(2L)));
        given(relay.publish(1L)).willThrow(
            new NotificationBusinessException(NotificationErrorCode.OUTBOX_PUBLISH_INTERRUPTED));

        job.publishDueRequests();

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        verify(relay, never()).publish(2L);
        verifyNoInteractions(reporter);
    }

    @Test
    void 첫_후보_처리_후_중단_요청이_들어오면_다음_후보를_시작하지_않는다() {
        given(outboxService.findAvailable(2)).willReturn(List.of(outbox(1L), outbox(2L)));
        doAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return true;
        }).when(relay).publish(1L);

        job.publishDueRequests();

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        verify(relay, never()).publish(2L);
    }

    @Test
    void 한_배치의_중복_ID는_한_번만_전달하고_담당_상실은_다음_후보를_막지_않는다() {
        given(outboxService.findAvailable(2)).willReturn(List.of(outbox(1L), outbox(1L), outbox(2L)));
        given(relay.publish(1L)).willThrow(new NotificationBusinessException(NotificationErrorCode.OUTBOX_CLAIM_LOST));

        job.publishDueRequests();

        verify(relay).publish(1L);
        verify(relay).publish(2L);
        verifyNoInteractions(reporter);
    }

    @Test
    void 동일_Job의_중복_진입을_막고_종료_후에는_다시_실행한다() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var finish = new java.util.concurrent.CountDownLatch(1);
        given(outboxService.findAvailable(2)).willReturn(List.of(outbox(1L)), List.of());
        doAnswer(invocation -> {
            entered.countDown();
            if (!finish.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new IllegalStateException("TEST_WAIT_TIMEOUT");
            }
            return true;
        }).when(relay).publish(1L);
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var first = executor.submit(job::publishDueRequests);
            try {
                assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                job.publishDueRequests();
                verify(outboxService).findAvailable(2);
            } finally {
                finish.countDown();
            }
            first.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        job.publishDueRequests();
        verify(outboxService, times(2)).findAvailable(2);
        verify(relay).publish(1L);
    }

    @Test
    void 담당_확보_DB_오류를_별도_코드로_보고하고_다음_후보를_처리한다() {
        given(outboxService.findAvailable(2)).willReturn(List.of(outbox(1L), outbox(2L)));
        given(relay.publish(1L))
            .willThrow(new NotificationBusinessException(NotificationErrorCode.OUTBOX_CLAIM_FAILED));

        job.publishDueRequests();

        verify(reporter).report(argThat(context -> context.errorCode() == NotificationFailureCode.OUTBOX_CLAIM_FAILED),
            eq(false));
        verify(relay).publish(2L);
    }

    private NotificationRequestOutbox outbox(Long id) {
        var outbox = new NotificationRequestOutbox("source-" + id, 7L, NotificationType.LIKE, "{}",
            LocalDateTime.now(ZoneOffset.UTC));
        ReflectionTestUtils.setField(outbox, "id", id);
        return outbox;
    }
}
