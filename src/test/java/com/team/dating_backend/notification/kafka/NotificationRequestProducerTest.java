package com.team.dating_backend.notification.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.config.NotificationKafkaProperties;
import com.team.dating_backend.notification.config.NotificationPublishProperties;
import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

@ExtendWith(MockitoExtension.class)
@Timeout(5)
class NotificationRequestProducerTest {

    private static final String TOPIC = "test-notification-create-requested-v1";

    @Mock
    private KafkaTemplate<Object, Object> kafkaTemplate;

    private NotificationPublishProperties publishProperties;
    private NotificationRequestProducer producer;

    @BeforeEach
    void setUp() {
        var kafkaProperties = new NotificationKafkaProperties();
        kafkaProperties.setCreateRequestedTopic(TOPIC);
        publishProperties = new NotificationPublishProperties();
        producer = new NotificationRequestProducer(kafkaTemplate, kafkaProperties, publishProperties);
    }

    @Test
    void 설정한_토픽과_수신자_key로_원본_요청을_발행하고_metadata를_반환한다() throws Exception {
        var request = request(101L);
        var result = sendResult(request);
        given(kafkaTemplate.send(TOPIC, "101", request)).willReturn(CompletableFuture.completedFuture(result));

        assertThat(producer.publishAndAwait(request)).isSameAs(result.getRecordMetadata());

        verify(kafkaTemplate).send(eq(TOPIC), eq("101"), same(request));
        verifyNoMoreInteractions(kafkaTemplate);
    }

    @ParameterizedTest
    @NullSource
    @MethodSource("requestsWithoutRecipient")
    void 요청이나_수신자가_null이면_errorCode로_거부하고_발행하지_않는다(NotificationCreateRequested request) {
        assertThatThrownBy(() -> producer.publishAndAwait(request))
            .isInstanceOfSatisfying(NotificationBusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INTERNAL_SERVER_ERROR));

        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void 발행_호출의_동기_예외를_그대로_전달한다() {
        var request = request(101L);
        var failure = new SerializationException();
        given(kafkaTemplate.send(TOPIC, "101", request)).willThrow(failure);

        assertThatThrownBy(() -> producer.publishAndAwait(request)).isSameAs(failure);

        verify(kafkaTemplate).send(TOPIC, "101", request);
        verifyNoMoreInteractions(kafkaTemplate);
    }

    @ParameterizedTest
    @MethodSource("publishFailures")
    void 최종_발행_실패는_원인을_보존한_ExecutionException으로_전달한다(Throwable failure) {
        var request = request(101L);
        given(kafkaTemplate.send(TOPIC, "101", request)).willReturn(CompletableFuture.failedFuture(failure));

        assertThatThrownBy(() -> producer.publishAndAwait(request))
            .isInstanceOf(ExecutionException.class).cause().isSameAs(failure);

        verify(kafkaTemplate).send(TOPIC, "101", request);
        verifyNoMoreInteractions(kafkaTemplate);
    }

    @Test
    void 결과_대기_timeout은_발행을_취소하거나_재발행하지_않고_늦은_완료를_허용한다() {
        var request = request(101L);
        var future = new CompletableFuture<SendResult<Object, Object>>();
        publishProperties.setResultTimeoutMs(1L);
        given(kafkaTemplate.send(TOPIC, "101", request)).willReturn(future);

        assertThatThrownBy(() -> producer.publishAndAwait(request)).isInstanceOf(TimeoutException.class);
        assertThat(future.isDone()).isFalse();
        assertThat(future.isCancelled()).isFalse();
        var result = sendResult(request);
        assertThat(future.complete(result)).isTrue();
        assertThat(future.getNow(null)).isSameAs(result);

        verify(kafkaTemplate).send(TOPIC, "101", request);
        verifyNoMoreInteractions(kafkaTemplate);
    }

    @Test
    void 결과_대기_interrupt는_상태를_복원하고_원래_예외를_전달한다() {
        var request = request(101L);
        var future = new CompletableFuture<SendResult<Object, Object>>();
        given(kafkaTemplate.send(TOPIC, "101", request)).willReturn(future);

        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> producer.publishAndAwait(request)).isInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(future.isCancelled()).isFalse();
        } finally {
            Thread.interrupted();
        }

        verify(kafkaTemplate).send(TOPIC, "101", request);
        verifyNoMoreInteractions(kafkaTemplate);
    }

    private static Stream<NotificationCreateRequested> requestsWithoutRecipient() {
        return Stream.of(request(null));
    }

    private static Stream<Throwable> publishFailures() {
        return Stream.of(new SerializationException(), new org.apache.kafka.common.errors.TimeoutException());
    }

    private static NotificationCreateRequested request(Long recipientUserId) {
        return new NotificationCreateRequested(1, "LIKE_SENT:501", recipientUserId, NotificationType.LIKE,
            "좋아요", "새로운 알림", "USER", 202L, Instant.parse("2026-10-20T01:02:03.123456789Z"));
    }

    private SendResult<Object, Object> sendResult(NotificationCreateRequested request) {
        var metadata = new RecordMetadata(new TopicPartition(TOPIC, 1), 42L, 0, 0L, 0, 0);
        return new SendResult<>(new ProducerRecord<>(TOPIC, "101", request), metadata);
    }
}
