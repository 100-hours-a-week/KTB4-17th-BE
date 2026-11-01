package com.team.dating_backend.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.team.dating_backend.chat.dto.event.ChatMessageCreatedKafkaEvent;
import com.team.dating_backend.chat.entity.ChatMessageOutbox;
import com.team.dating_backend.chat.enums.ChatOutboxFailureCode;
import com.team.dating_backend.chat.exception.ChatMessageKafkaEventCreationException;
import com.team.dating_backend.chat.repository.ChatMessageOutboxRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

class ChatMessageOutboxPublishJobTest {

    private static final Long MESSAGE_ID = 100L;
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 9, 26, 14, 30);

    private ChatMessageOutboxRepository outboxRepository;
    private ChatOutboxPublisherLockService lockService;
    private ChatMessageKafkaPublisher kafkaPublisher;
    private ChatMessageOutboxPublishJob job;
    private ChatMessageOutbox outbox;

    @BeforeEach
    void setUp() {
        outboxRepository = org.mockito.Mockito.mock(ChatMessageOutboxRepository.class);
        lockService = org.mockito.Mockito.mock(ChatOutboxPublisherLockService.class);
        kafkaPublisher = org.mockito.Mockito.mock(ChatMessageKafkaPublisher.class);
        job = new ChatMessageOutboxPublishJob(outboxRepository, lockService, kafkaPublisher);

        outbox = new ChatMessageOutbox(MESSAGE_ID, CREATED_AT);
        given(lockService.tryAcquire()).willReturn(true);
        given(lockService.tryRenew()).willReturn(true);
        given(outboxRepository
            .findByPublishedAtIsNullAndFailedAtIsNullAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscIdAsc(
                any(LocalDateTime.class), any(Pageable.class)))
            .willReturn(List.of(outbox));
        given(kafkaPublisher.publish(MESSAGE_ID))
            .willReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void DB_분산_락을_획득한_경우에만_Outbox를_조회한다() {
        given(lockService.tryAcquire()).willReturn(false);

        job.publishDueMessages();

        verifyNoInteractions(outboxRepository, kafkaPublisher);
    }

    @Test
    void Kafka_발행에_성공하면_Outbox를_완료한다() {
        job.publishDueMessages();

        assertThat(outbox.getPublishedAt()).isNotNull();
        assertThat(outbox.getFailedAt()).isNull();
        assertThat(outbox.getFailureCount()).isZero();
        verify(lockService).tryAcquire();
        verify(kafkaPublisher).publish(MESSAGE_ID);
        verify(outboxRepository).save(outbox);
    }

    @Test
    void Kafka_발행_결과를_받기_전에는_Outbox를_완료하지_않는다() throws Exception {
        CompletableFuture<SendResult<Long, ChatMessageCreatedKafkaEvent>> sendResult = new CompletableFuture<>();
        given(kafkaPublisher.publish(MESSAGE_ID)).willReturn(sendResult);

        try (var executor = Executors.newSingleThreadExecutor()) {
            var execution = executor.submit(job::publishDueMessages);
            verify(kafkaPublisher, timeout(1_000)).publish(MESSAGE_ID);

            assertThat(outbox.getPublishedAt()).isNull();
            verify(outboxRepository, never()).save(outbox);

            sendResult.complete(null);
            execution.get(1, TimeUnit.SECONDS);
        }

        assertThat(outbox.getPublishedAt()).isNotNull();
        verify(outboxRepository).save(outbox);
    }

    @Test
    void Kafka_발행을_기다리는_동안_DB_분산_락을_갱신하지_못하면_처리를_중단한다() throws Exception {
        CompletableFuture<SendResult<Long, ChatMessageCreatedKafkaEvent>> sendResult = new CompletableFuture<>();
        given(kafkaPublisher.publish(MESSAGE_ID)).willReturn(sendResult);
        given(lockService.tryRenew()).willReturn(false);

        try (var executor = Executors.newSingleThreadExecutor()) {
            var execution = executor.submit(job::publishDueMessages);
            execution.get(5, TimeUnit.SECONDS);
        }

        assertThat(outbox.getPublishedAt()).isNull();
        verify(lockService).tryAcquire();
        verify(lockService).tryRenew();
        verify(kafkaPublisher).publish(MESSAGE_ID);
        verify(outboxRepository, never()).save(outbox);
    }

    @Test
    void Kafka_이벤트를_생성하지_못하면_Outbox를_실패_상태로_변경한다() {
        given(kafkaPublisher.publish(MESSAGE_ID))
            .willThrow(new ChatMessageKafkaEventCreationException(
                ChatOutboxFailureCode.MESSAGE_NOT_FOUND));

        job.publishDueMessages();

        assertThat(outbox.getFailedAt()).isNotNull();
        assertThat(outbox.getLastFailureType())
            .isEqualTo(ChatOutboxFailureCode.MESSAGE_NOT_FOUND.name());
        verify(outboxRepository).save(outbox);
    }

    @Test
    void Kafka_발행에_실패한_Outbox는_다시_처리하도록_예약한다() {
        ReflectionTestUtils.setField(job, "maxFailures", 3);
        given(kafkaPublisher.publish(MESSAGE_ID)).willReturn(CompletableFuture.failedFuture(
            new KafkaException(ChatOutboxFailureCode.BROKER_PUBLISH_FAILED.name())));

        job.publishDueMessages();

        assertThat(outbox.getFailureCount()).isEqualTo(1);
        assertThat(outbox.getFailedAt()).isNull();
        assertThat(outbox.getPublishedAt()).isNull();
        assertThat(outbox.getNextAttemptAt()).isAfter(CREATED_AT);
        assertThat(outbox.getLastFailureType())
            .isEqualTo(ChatOutboxFailureCode.BROKER_PUBLISH_FAILED.name());
        verify(outboxRepository).save(outbox);
    }

    @Test
    void Outbox_발행_실패_횟수가_한도에_도달하면_실패_상태로_변경한다() {
        ReflectionTestUtils.setField(job, "maxFailures", 1);
        given(kafkaPublisher.publish(MESSAGE_ID)).willReturn(CompletableFuture.failedFuture(
            new KafkaException(ChatOutboxFailureCode.BROKER_PUBLISH_FAILED.name())));

        job.publishDueMessages();

        assertThat(outbox.getFailureCount()).isEqualTo(1);
        assertThat(outbox.getFailedAt()).isNotNull();
        assertThat(outbox.getLastFailureType())
            .isEqualTo(ChatOutboxFailureCode.BROKER_PUBLISH_FAILED.name());
        verify(outboxRepository).save(outbox);
    }

    @Test
    void DB에서_Outbox를_조회하지_못하면_다음_실행까지_대기한다() {
        given(outboxRepository
            .findByPublishedAtIsNullAndFailedAtIsNullAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscIdAsc(
                any(LocalDateTime.class), any(Pageable.class)))
            .willThrow(new DataAccessResourceFailureException(
                ChatOutboxFailureCode.DATABASE_ACCESS_FAILED.name()));

        job.publishDueMessages();
        job.publishDueMessages();

        verify(lockService).tryAcquire();
        verify(outboxRepository)
            .findByPublishedAtIsNullAndFailedAtIsNullAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscIdAsc(
                any(LocalDateTime.class), any(Pageable.class));
        verify(outboxRepository, never()).save(any(ChatMessageOutbox.class));
        verifyNoInteractions(kafkaPublisher);
    }
}
