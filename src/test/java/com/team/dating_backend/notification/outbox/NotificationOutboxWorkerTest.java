package com.team.dating_backend.notification.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.entity.NotificationRequestOutbox;
import com.team.dating_backend.notification.enums.NotificationErrorCode;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.exception.NotificationInvalidCreateRequestException;
import com.team.dating_backend.notification.service.NotificationCreationProcessor;
import com.team.dating_backend.notification.service.NotificationOutboxClaimService;
import com.team.dating_backend.notification.service.NotificationRequestOutboxService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
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
    private final NotificationOutboxWorker worker = new NotificationOutboxWorker(outboxes, processor, claims);
    private final String token = UUID.randomUUID().toString();
    private final NotificationCreateRequested request = new NotificationCreateRequested(
        1, "event:worker", 7L, NotificationType.LIKE, "제목", "본문", null, null, Instant.now());
    private NotificationRequestOutbox outbox;

    @BeforeEach
    void setUp() {
        outbox = new NotificationRequestOutbox("event:worker", 7L, NotificationType.LIKE, "{}", LocalDateTime.now());
        ReflectionTestUtils.setField(outbox, "id", 1L);
        ReflectionTestUtils.setField(outbox, "processingToken", token);
        ReflectionTestUtils.setField(outbox, "processingUntil", LocalDateTime.now().plusMinutes(1));
        given(outboxes.restore(outbox)).willReturn(request);
        given(claims.markCompleted(1L, token)).willReturn(true);
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
        order.verify(processor).process(request);
        order.verify(claims).markCompleted(1L, token);
        order.verifyNoMoreInteractions();
    }

    @Test
    void 복원_실패는_그대로_전달하고_생성과_완료를_호출하지_않는다() {
        var failure = new NotificationInvalidCreateRequestException();
        given(outboxes.restore(outbox)).willThrow(failure);

        assertThatThrownBy(() -> worker.process(outbox)).isSameAs(failure);

        verifyNoInteractions(processor, claims);
    }

    @ParameterizedTest
    @MethodSource("processingFailures")
    void 저장과_Delivery_실패는_그대로_전달하고_완료나_점유_해제를_호출하지_않는다(Exception failure)
        throws Exception {
        doThrow(failure).when(processor).process(request);

        assertThatThrownBy(() -> worker.process(outbox)).isSameAs(failure);

        assertThat(Thread.currentThread().isInterrupted()).isEqualTo(failure instanceof InterruptedException);
        verifyNoInteractions(claims);
    }

    @Test
    void 완료_거부는_점유권_상실로_전달한다() {
        given(claims.markCompleted(1L, token)).willReturn(false);

        assertThatThrownBy(() -> worker.process(outbox))
            .isInstanceOfSatisfying(NotificationBusinessException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(NotificationErrorCode.OUTBOX_CLAIM_LOST));
    }

    @Test
    void 완료_DB_오류는_성공으로_바꾸지_않고_그대로_전달한다() {
        var failure = new DataAccessResourceFailureException("완료 갱신 실패");
        given(claims.markCompleted(1L, token)).willThrow(failure);

        assertThatThrownBy(() -> worker.process(outbox)).isSameAs(failure);
    }

    @Test
    void 이미_중단된_실행은_복원_전부터_처리하지_않는다() {
        Thread.currentThread().interrupt();

        assertThatThrownBy(() -> worker.process(outbox)).isInstanceOf(InterruptedException.class);

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        verifyNoInteractions(outboxes, processor, claims);
    }

    @Test
    void Processor_종료_시_인터럽트가_설정되어_있으면_완료하지_않는다() throws Exception {
        doAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return null;
        }).when(processor).process(request);

        assertThatThrownBy(() -> worker.process(outbox)).isInstanceOf(InterruptedException.class);

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        verifyNoInteractions(claims);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 요청이_없거나_점유되지_않았으면_처리하지_않는다(boolean missing) {
        var candidate = missing
            ? null
            : new NotificationRequestOutbox(
                "event:worker", 7L, NotificationType.LIKE, "{}", LocalDateTime.now());

        assertThatThrownBy(() -> worker.process(candidate)).isInstanceOf(NotificationBusinessException.class);

        verifyNoInteractions(outboxes, processor, claims);
    }

    private static Stream<Exception> processingFailures() {
        return Stream.of(new NotificationInvalidCreateRequestException(),
            new DataAccessResourceFailureException("저장 실패"), new ExecutionException(new IllegalStateException()),
            new TimeoutException(), new InterruptedException());
    }
}
