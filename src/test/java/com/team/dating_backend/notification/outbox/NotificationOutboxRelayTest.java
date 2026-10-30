package com.team.dating_backend.notification.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.entity.NotificationRequestOutbox;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.exception.NotificationInvalidCreateRequestException;
import com.team.dating_backend.notification.kafka.NotificationRequestProducer;
import com.team.dating_backend.notification.service.NotificationRequestOutboxService;
import java.time.Instant;
import java.time.LocalDateTime;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
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
    private NotificationRequestOutbox outbox;
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
    void 발행_예외는_그대로_전달하고_완료_기록이나_즉시_재시도를_하지_않는다(Exception failure) throws Exception {
        givenPendingRequest();
        given(producer.publishAndAwait(REQUEST)).willThrow(failure);

        assertThatThrownBy(() -> relay.publish(OUTBOX_ID)).isSameAs(failure);

        verify(outboxService).findDueById(eq(OUTBOX_ID), any(LocalDateTime.class));
        verify(outboxService).restore(outbox);
        verify(producer).publishAndAwait(REQUEST);
        verifyNoMoreInteractions(outboxService, producer);
    }

    @Test
    void 복원에_실패하면_Kafka_발행과_완료_기록을_하지_않는다() {
        given(outboxService.findDueById(eq(OUTBOX_ID), any(LocalDateTime.class))).willReturn(Optional.of(outbox));
        var failure = new NotificationInvalidCreateRequestException();
        given(outboxService.restore(outbox)).willThrow(failure);

        assertThatThrownBy(() -> relay.publish(OUTBOX_ID)).isSameAs(failure);

        verify(outboxService).findDueById(eq(OUTBOX_ID), any(LocalDateTime.class));
        verify(outboxService).restore(outbox);
        verifyNoMoreInteractions(outboxService);
        verifyNoInteractions(producer);
    }

    @Test
    void 성공_결과가_없으면_완료를_기록하지_않는다() throws Exception {
        givenPendingRequest();

        assertThatThrownBy(() -> relay.publish(OUTBOX_ID)).isInstanceOf(NotificationBusinessException.class);

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

        assertThatThrownBy(() -> relay.publish(OUTBOX_ID)).isSameAs(failure);

        verify(producer).publishAndAwait(REQUEST);
        verify(outboxService).markPublished(eq(OUTBOX_ID), any(LocalDateTime.class));
        verifyNoMoreInteractions(producer);
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

    private static Stream<Exception> publishFailures() {
        return Stream.of(new ExecutionException(new IllegalStateException()), new TimeoutException(),
            new InterruptedException(), new IllegalStateException());
    }
}
