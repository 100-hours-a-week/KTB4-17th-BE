package com.team.dating_backend.notification.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.notification.exception.NotificationInvalidCreateRequestException;
import com.team.dating_backend.notification.exception.NotificationRetryableCreationException;
import com.team.dating_backend.notification.service.NotificationCreationProcessor;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;

class NotificationCreationConsumerTest {

    private static final NotificationCreateRequested REQUEST = new NotificationCreateRequested(
        1, "source", 7L, NotificationType.LIKE, "제목", "본문", null, null,
        Instant.parse("2026-10-22T01:00:00Z"));
    private static final ConsumerRecord<Long, NotificationCreateRequested> RECORD = new ConsumerRecord<>("creation",
        0, 0L, 7L, REQUEST);

    private final NotificationCreationProcessor processor = mock(NotificationCreationProcessor.class);
    private final NotificationCreationConsumer consumer = new NotificationCreationConsumer(
        processor, new NotificationCreationFailureClassifier());

    @ParameterizedTest
    @MethodSource("retryableFailures")
    void 일시_오류는_errorCode와_원인을_보존한_Retry_표식으로_전달한다(Exception cause) throws Exception {
        doThrow(cause).when(processor).process(REQUEST);

        var failure = assertThrows(NotificationRetryableCreationException.class, () -> consumer.consume(RECORD));

        assertThat(failure.getErrorCode()).isEqualTo(CommonErrorCode.INTERNAL_SERVER_ERROR);
        assertThat(failure.getMessage()).isNull();
        assertThat(failure.getCause()).isSameAs(cause);
    }

    @ParameterizedTest
    @MethodSource("nonRetryableFailures")
    void 영구_오류와_중첩된_중단_예외는_원본을_그대로_전달한다(Exception failure) throws Exception {
        doThrow(failure).when(processor).process(REQUEST);

        assertThatThrownBy(() -> consumer.consume(RECORD)).isSameAs(failure);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    void 직접_전달된_중단_예외는_interrupt를_복원하고_그대로_전달한다() throws Exception {
        var failure = new InterruptedException();
        doThrow(failure).when(processor).process(REQUEST);
        try {
            assertThatThrownBy(() -> consumer.consume(RECORD)).isSameAs(failure);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void Error는_Retry_표식으로_변환하지_않는다() throws Exception {
        var failure = new AssertionError();
        doThrow(failure).when(processor).process(REQUEST);

        assertThatThrownBy(() -> consumer.consume(RECORD)).isSameAs(failure);
    }

    private static Stream<Exception> retryableFailures() {
        return Stream.of(new TimeoutException(), new ExecutionException(new TimeoutException()),
            new QueryTimeoutException(null));
    }

    private static Stream<Exception> nonRetryableFailures() {
        return Stream.of(new NotificationInvalidCreateRequestException(), new DataAccessResourceFailureException(null),
            new ExecutionException(new IllegalStateException()), new ExecutionException(new InterruptedException()));
    }
}
