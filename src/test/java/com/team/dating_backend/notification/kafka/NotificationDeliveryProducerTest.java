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
import com.team.dating_backend.notification.config.NotificationCreationProperties;
import com.team.dating_backend.notification.config.NotificationKafkaProperties;
import com.team.dating_backend.notification.dto.event.NotificationDeliveryEvent;
import com.team.dating_backend.notification.dto.response.NotificationEventResponse;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import java.time.LocalDateTime;
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
class NotificationDeliveryProducerTest {

    private static final String TOPIC = "test-notification-delivery-v1";
    private static final NotificationEventResponse NOTIFICATION = new NotificationEventResponse(
        10L, "LIKE", "제목", "본문", "USER", 2L, null, LocalDateTime.of(2026, 10, 21, 1, 0));
    private static final NotificationDeliveryEvent EVENT = new NotificationDeliveryEvent(1, "event-1", 7L,
        NOTIFICATION);

    @Mock
    private KafkaTemplate<Object, Object> kafkaTemplate;

    private NotificationCreationProperties properties;
    private NotificationDeliveryProducer producer;

    @BeforeEach
    void setUp() {
        var topics = new NotificationKafkaProperties();
        topics.setDeliveryTopic(TOPIC);
        properties = new NotificationCreationProperties();
        producer = new NotificationDeliveryProducer(kafkaTemplate, topics, properties);
    }

    @Test
    void delivery_토픽과_수신자_key로_원본_이벤트를_발행하고_metadata를_반환한다() throws Exception {
        var result = sendResult();
        given(kafkaTemplate.send(TOPIC, "7", EVENT)).willReturn(CompletableFuture.completedFuture(result));

        assertThat(producer.publishAndAwait(EVENT)).isSameAs(result.getRecordMetadata());

        verify(kafkaTemplate).send(eq(TOPIC), eq("7"), same(EVENT));
        verifyNoMoreInteractions(kafkaTemplate);
    }

    @ParameterizedTest
    @NullSource
    @MethodSource("invalidEvents")
    void 잘못된_envelope는_errorCode로_거부하고_발행하지_않는다(NotificationDeliveryEvent event) {
        assertThatThrownBy(() -> producer.publishAndAwait(event))
            .isInstanceOfSatisfying(NotificationBusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INTERNAL_SERVER_ERROR));

        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void send_호출_실패는_원래_예외를_전달한다() {
        var failure = new SerializationException();
        given(kafkaTemplate.send(TOPIC, "7", EVENT)).willThrow(failure);

        assertThatThrownBy(() -> producer.publishAndAwait(EVENT)).isSameAs(failure);

        verify(kafkaTemplate).send(TOPIC, "7", EVENT);
        verifyNoMoreInteractions(kafkaTemplate);
    }

    @Test
    void 비동기_발행_실패는_원인을_보존해_전달한다() {
        var failure = new org.apache.kafka.common.errors.TimeoutException();
        given(kafkaTemplate.send(TOPIC, "7", EVENT)).willReturn(CompletableFuture.failedFuture(failure));

        assertThatThrownBy(() -> producer.publishAndAwait(EVENT))
            .isInstanceOf(ExecutionException.class).cause().isSameAs(failure);

        verify(kafkaTemplate).send(TOPIC, "7", EVENT);
        verifyNoMoreInteractions(kafkaTemplate);
    }

    @Test
    void 결과_대기_timeout은_발행을_취소하거나_재발행하지_않고_늦은_완료를_허용한다() {
        var future = new CompletableFuture<SendResult<Object, Object>>();
        properties.setDeliveryResultTimeoutMs(1L);
        given(kafkaTemplate.send(TOPIC, "7", EVENT)).willReturn(future);

        assertThatThrownBy(() -> producer.publishAndAwait(EVENT)).isInstanceOf(TimeoutException.class);
        assertThat(future.isDone()).isFalse();
        assertThat(future.isCancelled()).isFalse();
        var result = sendResult();
        assertThat(future.complete(result)).isTrue();
        assertThat(future.getNow(null)).isSameAs(result);

        verify(kafkaTemplate).send(TOPIC, "7", EVENT);
        verifyNoMoreInteractions(kafkaTemplate);
    }

    @Test
    void interrupt_상태를_복원하고_중단을_전달한다() {
        var future = new CompletableFuture<SendResult<Object, Object>>();
        given(kafkaTemplate.send(TOPIC, "7", EVENT)).willReturn(future);

        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> producer.publishAndAwait(EVENT)).isInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(future.isCancelled()).isFalse();
        } finally {
            Thread.interrupted();
        }

        verify(kafkaTemplate).send(TOPIC, "7", EVENT);
        verifyNoMoreInteractions(kafkaTemplate);
    }

    private static Stream<NotificationDeliveryEvent> invalidEvents() {
        return Stream.of(
            new NotificationDeliveryEvent(2, "event-1", 7L, NOTIFICATION),
            new NotificationDeliveryEvent(1, " ", 7L, NOTIFICATION),
            new NotificationDeliveryEvent(1, "event-1", 0L, NOTIFICATION),
            new NotificationDeliveryEvent(1, "event-1", 7L, null),
            new NotificationDeliveryEvent(1, "event-1", 7L, new NotificationEventResponse(
                null, "LIKE", "제목", "본문", null, null, null, NOTIFICATION.createdAt())));
    }

    private SendResult<Object, Object> sendResult() {
        var metadata = new RecordMetadata(new TopicPartition(TOPIC, 1), 42L, 0, 0L, 0, 0);
        return new SendResult<>(new ProducerRecord<>(TOPIC, "7", EVENT), metadata);
    }
}
