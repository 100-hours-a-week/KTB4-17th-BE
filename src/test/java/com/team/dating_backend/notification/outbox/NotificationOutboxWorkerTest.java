package com.team.dating_backend.notification.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.entity.NotificationRequestOutbox;
import com.team.dating_backend.notification.enums.NotificationErrorCode;
import com.team.dating_backend.notification.enums.NotificationFailureCode;
import com.team.dating_backend.notification.enums.NotificationOutboxStatus;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.exception.NotificationInvalidCreateRequestException;
import com.team.dating_backend.notification.service.NotificationCreationProcessor;
import com.team.dating_backend.notification.service.NotificationOutboxClaimService;
import com.team.dating_backend.notification.service.NotificationOutboxFailureService;
import com.team.dating_backend.notification.service.NotificationRequestOutboxService;
import com.team.dating_backend.notification.kafka.NotificationCreationFailureClassifier;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

class NotificationOutboxWorkerTest {

    private final NotificationRequestOutboxService outboxes = mock(NotificationRequestOutboxService.class);
    private final NotificationCreationProcessor processor = mock(NotificationCreationProcessor.class);
    private final NotificationOutboxClaimService claims = mock(NotificationOutboxClaimService.class);
    private final NotificationOutboxFailureService failures = mock(NotificationOutboxFailureService.class);
    private final NotificationOutboxWorker worker = new NotificationOutboxWorker(outboxes, processor, claims, failures,
        new NotificationOutboxWorkerFailureClassifier(new NotificationCreationFailureClassifier()));
    private final String token = UUID.randomUUID().toString();
    private final NotificationCreateRequested request = new NotificationCreateRequested("event:worker", 7L,
        NotificationType.LIKE, "제목", "본문", null, null, Instant.now());
    private NotificationRequestOutbox outbox;

    @BeforeEach
    void setUp() {
        Thread.interrupted();
        outbox = new NotificationRequestOutbox("event:worker", 7L, NotificationType.LIKE, "{}", LocalDateTime.now());
        ReflectionTestUtils.setField(outbox, "id", 1L);
        ReflectionTestUtils.setField(outbox, "processingToken", token);
        ReflectionTestUtils.setField(outbox, "processingUntil", LocalDateTime.now().plusMinutes(1));
        given(outboxes.restore(outbox)).willReturn(request);
        given(claims.markCompleted(1L, token)).willReturn(true);
        given(failures.recordWorkerFailure(eq(1L), eq(token), any(), anyBoolean()))
            .willAnswer(invocation -> Optional.of(new NotificationOutboxFailureService.WorkerFailureResult(1,
                invocation.<Boolean>getArgument(3)
                    ? NotificationOutboxStatus.PENDING
                    : NotificationOutboxStatus.FAILED)));
    }

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void 복원과_생성_및_전달이_정상_종료한_뒤_현재_토큰으로_완료한다() throws Exception {
        worker.process(outbox);

        var order = inOrder(outboxes, processor, claims);
        order.verify(outboxes).restore(outbox);
        order.verify(processor).process(eq(request), any());
        order.verify(claims).markCompleted(1L, token);
        order.verifyNoMoreInteractions();
        verifyNoInteractions(failures);
    }

    @Test
    void 복원_실패는_그대로_전달하고_생성과_완료를_호출하지_않는다() {
        var failure = new NotificationInvalidCreateRequestException();
        given(outboxes.restore(outbox)).willThrow(failure);

        assertThatThrownBy(() -> worker.process(outbox)).isSameAs(failure);

        verifyNoInteractions(processor, claims);
        verify(failures).recordWorkerFailure(1L, token, NotificationFailureCode.OUTBOX_RESTORE_FAILED, false);
    }

    @ParameterizedTest
    @MethodSource("processingFailures")
    void Processor_실패는_분류하여_기록하고_중단은_실패_횟수에서_제외한다(Exception failure)
        throws Exception {
        doThrow(failure).when(processor).process(eq(request), any());

        assertThatThrownBy(() -> worker.process(outbox)).isSameAs(failure);

        boolean interrupted = failure instanceof InterruptedException
            || (failure instanceof ExecutionException
                && failure.getCause() instanceof org.apache.kafka.common.errors.InterruptException);
        assertThat(Thread.currentThread().isInterrupted()).isEqualTo(interrupted);
        verify(claims, never()).markCompleted(any(), any());
        if (interrupted) {
            verify(claims).release(1L, token);
            verifyNoInteractions(failures);
        } else {
            verify(failures).recordWorkerFailure(1L, token, NotificationFailureCode.OUTBOX_CREATION_FAILED,
                failure instanceof TimeoutException);
            verifyNoInteractions(claims);
        }
    }

    @Test
    void 완료_거부는_점유권_상실로_전달한다() {
        given(claims.markCompleted(1L, token)).willReturn(false);

        assertThatThrownBy(() -> worker.process(outbox))
            .isInstanceOfSatisfying(NotificationBusinessException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(NotificationErrorCode.OUTBOX_CLAIM_LOST));
        verifyNoInteractions(failures);
        verify(claims, never()).release(any(), any());
    }

    @Test
    void 완료_DB_오류는_성공으로_바꾸지_않고_그대로_전달한다() {
        var failure = new DataAccessResourceFailureException("완료 갱신 실패");
        given(claims.markCompleted(1L, token)).willThrow(failure);

        assertThatThrownBy(() -> worker.process(outbox)).isSameAs(failure);
        verify(failures).recordWorkerFailure(1L, token, NotificationFailureCode.OUTBOX_STATE_UPDATE_FAILED, false);
    }

    @Test
    void 이미_중단된_실행은_복원_전부터_처리하지_않는다() {
        Thread.currentThread().interrupt();

        assertThatThrownBy(() -> worker.process(outbox)).isInstanceOf(InterruptedException.class);

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        verifyNoInteractions(outboxes, processor, failures);
        verify(claims).release(1L, token);
    }

    @Test
    void Processor_종료_시_인터럽트가_설정되어_있으면_완료하지_않는다() throws Exception {
        doAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return null;
        }).when(processor).process(eq(request), any());

        assertThatThrownBy(() -> worker.process(outbox)).isInstanceOf(InterruptedException.class);

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        verify(claims).release(1L, token);
        verify(claims, never()).markCompleted(any(), any());
        verifyNoInteractions(failures);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 요청이_없거나_점유되지_않았으면_처리하지_않는다(boolean missing) {
        var candidate = missing
            ? null
            : new NotificationRequestOutbox(
                "event:worker", 7L, NotificationType.LIKE, "{}", LocalDateTime.now());

        assertThatThrownBy(() -> worker.process(candidate)).isInstanceOf(NotificationBusinessException.class);

        verifyNoInteractions(outboxes, processor, claims, failures);
    }

    @Test
    void Delivery_단계의_실패는_생성_실패와_구분하여_기록한다() throws Exception {
        doAnswer(invocation -> {
            invocation.<java.util.function.Consumer<NotificationCreationProcessor.Stage>>getArgument(1)
                .accept(NotificationCreationProcessor.Stage.DELIVERY);
            throw new TimeoutException();
        }).when(processor).process(eq(request), any());

        assertThatThrownBy(() -> worker.process(outbox)).isInstanceOf(TimeoutException.class);

        verify(failures).recordWorkerFailure(1L, token, NotificationFailureCode.OUTBOX_DELIVERY_FAILED, true);
        verifyNoInteractions(claims);
    }

    @Test
    void 실패_기록_DB_오류는_원본에_첨부하고_점유를_해제하거나_완료하지_않는다() throws Exception {
        var original = new TimeoutException();
        var recording = new DataAccessResourceFailureException("실패 기록 불가");
        doThrow(original).when(processor).process(eq(request), any());
        given(failures.recordWorkerFailure(eq(1L), eq(token), any(), anyBoolean())).willThrow(recording);

        assertThatThrownBy(() -> worker.process(outbox)).isSameAs(original);

        assertThat(original.getSuppressed()).containsExactly(recording);
        verifyNoInteractions(claims);
    }

    @Test
    void 실패_기록이_거부되면_원본을_보존한_점유권_상실로_전달한다() throws Exception {
        var original = new TimeoutException();
        doThrow(original).when(processor).process(eq(request), any());
        given(failures.recordWorkerFailure(eq(1L), eq(token), any(), anyBoolean())).willReturn(Optional.empty());

        assertThatThrownBy(() -> worker.process(outbox))
            .isInstanceOfSatisfying(NotificationBusinessException.class, failure -> {
                assertThat(failure.getErrorCode()).isEqualTo(NotificationErrorCode.OUTBOX_CLAIM_LOST);
                assertThat(failure.getCause()).isSameAs(original);
            });

        verifyNoInteractions(claims);
    }

    @Test
    void 중단_시_점유_해제는_인터럽트_표시를_잠시_해제하고_오류가_나도_복원한다() throws Exception {
        var interruption = new InterruptedException();
        var releasing = new DataAccessResourceFailureException("해제 실패");
        doThrow(interruption).when(processor).process(eq(request), any());
        given(claims.release(1L, token)).willAnswer(invocation -> {
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
            throw releasing;
        });

        assertThatThrownBy(() -> worker.process(outbox)).isSameAs(interruption);

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        assertThat(interruption.getSuppressed()).containsExactly(releasing);
        verifyNoInteractions(failures);
    }

    private static Stream<Exception> processingFailures() {
        return Stream.of(new NotificationInvalidCreateRequestException(),
            new DataAccessResourceFailureException("저장 실패"), new ExecutionException(new IllegalStateException()),
            new TimeoutException(), new InterruptedException(),
            new ExecutionException(new org.apache.kafka.common.errors.InterruptException(new InterruptedException())));
    }
}
