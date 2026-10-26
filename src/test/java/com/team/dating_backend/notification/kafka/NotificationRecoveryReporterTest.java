package com.team.dating_backend.notification.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.team.dating_backend.notification.alert.NotificationFailureContext;
import com.team.dating_backend.notification.alert.NotificationFailureContext.KafkaLocation;
import com.team.dating_backend.notification.alert.NotificationFailureReporter;
import com.team.dating_backend.notification.config.NotificationKafkaProperties;
import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.enums.NotificationFailureCode;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.notification.exception.NotificationRetryableCreationException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.TimestampedException;
import org.springframework.kafka.retrytopic.RetryTopicHeaders;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.SendResult;

class NotificationRecoveryReporterTest {

    private static final String TOPIC = "notification-create";
    private final NotificationFailureReporter reporter = mock(NotificationFailureReporter.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final KafkaOperations<Object, Object> template = mock(KafkaOperations.class);
    private final NotificationKafkaProperties properties = new NotificationKafkaProperties();
    private final NotificationRecoveryReporter recoveryReporter = new NotificationRecoveryReporter(
        reporter, new NotificationCreationFailureClassifier(), properties, registry);

    @AfterEach
    void close() {
        registry.close();
        Thread.interrupted();
    }

    @Test
    void DLT_결과_확인까지_보고하지_않고_실제_저장_위치를_사용한다() throws Exception {
        var pending = new CompletableFuture<SendResult<Object, Object>>();
        when(template.send(any(ProducerRecord.class))).thenReturn(pending);
        var recoverer = recoverer(TOPIC + "-dlt");
        try (var executor = Executors.newSingleThreadExecutor()) {
            var task = executor.submit(() -> recoverer.accept(record(TOPIC, "source"), null, processingFailure()));
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> verify(template).send(any(ProducerRecord.class)));
            verifyNoInteractions(reporter);
            assertThat(task.isDone()).isFalse();
            pending.complete(result(TOPIC + "-dlt", 2, 91));
            task.get(2, TimeUnit.SECONDS);
        }
        var context = captured();
        assertThat(context.errorCode()).isEqualTo(NotificationFailureCode.DLT_RECORDED);
        assertThat(context.sourceEventId()).isEqualTo("source");
        assertThat(context.exceptionType()).isEqualTo("TimeoutException");
        assertThat(context.kafkaDetails().consumerGroup()).isEqualTo("current-group");
        assertThat(context.kafkaDetails().destinationLocation()).isEqualTo(new KafkaLocation(TOPIC + "-dlt", 2, 91L));
        assertThat(context.kafkaDetails().inputLocation()).isEqualTo(new KafkaLocation(TOPIC, 0, 4L));
        assertThat(context.kafkaDetails().originalLocation()).isEqualTo(context.kafkaDetails().inputLocation());
        assertThat(context.kafkaDetails().attempt()).isEqualTo(1);
        assertThat(context.kafkaDetails().retryable()).isTrue();
    }

    @Test
    void 정상_Retry_인계는_최종_실패로_보고하지_않는다() {
        when(template.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.completedFuture(result(TOPIC + "-retry", 0, 3)));
        recoverer(TOPIC + "-retry").accept(record(TOPIC, "source"), null, processingFailure());
        verifyNoInteractions(reporter);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-retry", "-dlt"})
    void 인계_실패는_코드로_구분하고_동일한_예외를_재전파한다(String suffix) {
        var failure = new IllegalStateException("private-handoff");
        when(template.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.failedFuture(failure));
        assertThatThrownBy(() -> recoverer(TOPIC + suffix).accept(record(TOPIC, "source"), null, processingFailure()))
            .isInstanceOf(KafkaException.class).hasRootCause(failure);
        var context = captured();
        assertThat(context.errorCode()).isEqualTo(suffix.equals("-dlt")
            ? NotificationFailureCode.DLT_PUBLISH_FAILED
            : NotificationFailureCode.RETRY_PUBLISH_FAILED);
        assertThat(context.exceptionType()).isEqualTo("KafkaException");
        assertThat(context.kafkaDetails().processingExceptionType()).isEqualTo("TimeoutException");
        assertThat(context.kafkaDetails().destinationLocation().offset()).isNull();
    }

    @Test
    void timeout_후_늦게_완료되어도_DLT_성공을_뒤늦게_보고하지_않는다() {
        var pending = new CompletableFuture<SendResult<Object, Object>>();
        when(template.send(any(ProducerRecord.class))).thenReturn(pending);
        var recoverer = recoverer(TOPIC + "-dlt");
        recoverer.setWaitForSendResultTimeout(Duration.ofMillis(10));
        assertThatThrownBy(() -> recoverer.accept(record(TOPIC, "source"), null, processingFailure()))
            .isInstanceOf(KafkaException.class).hasRootCauseInstanceOf(TimeoutException.class);
        pending.complete(result(TOPIC + "-dlt", 0, 7));
        assertThat(captured().errorCode()).isEqualTo(NotificationFailureCode.DLT_PUBLISH_FAILED);
    }

    @Test
    void interrupt를_보존하고_인계_성공으로_보고하지_않는다() {
        when(template.send(any(ProducerRecord.class))).thenReturn(new CompletableFuture<>());
        Thread.currentThread().interrupt();
        assertThatThrownBy(() -> recoverer(TOPIC + "-dlt").accept(record(TOPIC, "source"), null, processingFailure()))
            .isInstanceOf(KafkaException.class).hasRootCauseInstanceOf(InterruptedException.class);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        assertThat(captured().errorCode()).isEqualTo(NotificationFailureCode.DLT_PUBLISH_FAILED);
    }

    @Test
    void 보고_실패가_성공한_DLT나_원래_인계_실패를_바꾸지_않는다() {
        doThrow(new IllegalStateException("private-report"))
            .when(reporter).report(any(NotificationFailureContext.class));
        var recoverer = recoverer(TOPIC + "-dlt");
        when(template.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.completedFuture(result(TOPIC + "-dlt", 0, 1)));
        assertThatCode(() -> recoverer.accept(record(TOPIC, "success"), null, processingFailure()))
            .doesNotThrowAnyException();
        var failure = new IllegalStateException("original");
        when(template.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.failedFuture(failure));
        assertThatThrownBy(() -> recoverer.accept(record(TOPIC, "failure"), null, processingFailure()))
            .hasRootCause(failure);
        assertThat(registry.get("notification.reporting.failures").tag("operation", "RECOVERY_CONTEXT")
            .counter().count()).isEqualTo(2);
    }

    @Test
    void Retry_입력의_원본_위치와_현재_시도를_구분하고_raw_값은_역파싱하지_않는다() {
        var record = new ConsumerRecord<Object, Object>(TOPIC + "-retry", 0, 4, "7", new byte[]{1, 2});
        record.headers().add(KafkaHeaders.ORIGINAL_TOPIC, TOPIC.getBytes(StandardCharsets.UTF_8));
        record.headers().add(KafkaHeaders.ORIGINAL_PARTITION, ByteBuffer.allocate(4).putInt(1).array());
        record.headers().add(KafkaHeaders.ORIGINAL_OFFSET, ByteBuffer.allocate(8).putLong(77).array());
        record.headers().add(RetryTopicHeaders.DEFAULT_HEADER_ATTEMPTS, ByteBuffer.allocate(4).putInt(3).array());
        when(template.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.completedFuture(result(TOPIC + "-dlt", 0, 9)));
        recoverer(TOPIC + "-dlt").accept(record, null, new IllegalArgumentException("private-body"));
        var context = captured();
        assertThat(context.sourceEventId()).isNull();
        assertThat(context.kafkaDetails().originalLocation()).isEqualTo(new KafkaLocation(TOPIC, 1, 77L));
        assertThat(context.kafkaDetails().inputLocation()).isEqualTo(new KafkaLocation(TOPIC + "-retry", 0, 4L));
        assertThat(context.kafkaDetails().attempt()).isEqualTo(3);
        assertThat(context.kafkaDetails().retryable()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 32768})
    void 비정상_시도와_위치_헤더는_추정하지_않는다(int attempt) {
        var record = record(TOPIC + "-retry", "source");
        record.headers().add(RetryTopicHeaders.DEFAULT_HEADER_ATTEMPTS, ByteBuffer.allocate(4).putInt(attempt).array());
        record.headers().add(KafkaHeaders.ORIGINAL_TOPIC, TOPIC.getBytes(StandardCharsets.UTF_8));
        record.headers().add(KafkaHeaders.ORIGINAL_PARTITION, new byte[]{1});
        record.headers().add(KafkaHeaders.ORIGINAL_OFFSET, ByteBuffer.allocate(8).putLong(7).array());
        when(template.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.completedFuture(result(TOPIC + "-dlt", 0, 1)));
        recoverer(TOPIC + "-dlt").accept(record, null, processingFailure());
        var details = captured().kafkaDetails();
        assertThat(details.attempt()).isNull();
        assertThat(details.originalLocation()).isEqualTo(new KafkaLocation(TOPIC, null, null));
    }

    @Test
    void 동시_호출에서_요청과_목적지_위치가_섞이지_않는다() throws Exception {
        var first = new CompletableFuture<SendResult<Object, Object>>();
        var second = new CompletableFuture<SendResult<Object, Object>>();
        when(template.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            ProducerRecord<?, ?> record = invocation.getArgument(0);
            return ((NotificationCreateRequested) record.value()).sourceEventId().equals("first") ? first : second;
        });
        var recoverer = recoverer(TOPIC + "-dlt");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var task1 = executor.submit(() -> recoverer.accept(record(TOPIC, "first"), null, processingFailure()));
            var task2 = executor.submit(() -> recoverer.accept(record(TOPIC, "second"), null, processingFailure()));
            await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> verify(template, times(2)).send(any(ProducerRecord.class)));
            second.complete(result(TOPIC + "-dlt", 1, 22));
            first.complete(result(TOPIC + "-dlt", 0, 11));
            task1.get(2, TimeUnit.SECONDS);
            task2.get(2, TimeUnit.SECONDS);
        }
        var contexts = ArgumentCaptor.forClass(NotificationFailureContext.class);
        verify(reporter, times(2)).report(contexts.capture());
        assertThat(contexts.getAllValues())
            .allSatisfy(context -> assertThat(context.kafkaDetails().destinationLocation().offset())
                .isEqualTo(context.sourceEventId().equals("first") ? 11L : 22L));
    }

    @Test
    void 목적지_결정_실패는_원래_예외를_유지하고_다음_호출에_상태를_남기지_않는다() {
        properties.setCreateRequestedTopic(TOPIC);
        var failure = new IllegalStateException();
        var recoverer = recoveryReporter.createRecoverer(record -> template, (record, exception) -> {
            if (((NotificationCreateRequested) record.value()).sourceEventId().equals("bad")) {
                throw failure;
            }
            return new TopicPartition(TOPIC + "-dlt", 0);
        });
        when(template.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.completedFuture(result(TOPIC + "-dlt", 0, 5)));
        assertThatThrownBy(() -> recoverer.accept(record(TOPIC, "bad"), null, processingFailure())).isSameAs(failure);
        recoverer.accept(record(TOPIC, "good"), null, processingFailure());
        assertThat(captured().sourceEventId()).isEqualTo("good");
        assertThat(registry.get("notification.reporting.failures").counter().count()).isEqualTo(1);
    }

    @Test
    void Error는_보고_실패로_흡수하지_않는다() {
        var failure = new AssertionError();
        when(template.send(any(ProducerRecord.class))).thenThrow(failure);
        assertThatThrownBy(() -> recoverer(TOPIC + "-dlt").accept(record(TOPIC, "error"), null, processingFailure()))
            .isSameAs(failure);
        verifyNoInteractions(reporter);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void Retry_헤더_누락과_null과_잘못된_길이와_구버전을_처리한다(int scenario) {
        var record = record(TOPIC + "-retry", "source");
        if (scenario != 0) {
            record.headers().add(RetryTopicHeaders.DEFAULT_HEADER_ATTEMPTS,
                scenario == 1 ? null : scenario == 2 ? new byte[]{1, 2} : new byte[]{2});
        }
        when(template.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.completedFuture(result(TOPIC + "-dlt", 0, 1)));
        recoverer(TOPIC + "-dlt").accept(record, null, processingFailure());
        assertThat(captured().kafkaDetails().attempt()).isEqualTo(scenario == 3 ? 2 : null);
    }

    private DeadLetterPublishingRecoverer recoverer(String destination) {
        properties.setCreateRequestedTopic(TOPIC);
        ProducerFactory<Object, Object> factory = mock(ProducerFactory.class);
        when(factory.getConfigurationProperties()).thenReturn(Map.of(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 1));
        when(template.getProducerFactory()).thenReturn(factory);
        var recoverer = recoveryReporter.createRecoverer(record -> template,
            (record, exception) -> new TopicPartition(destination, 0));
        recoverer.setTimeoutBuffer(0);
        recoverer.setWaitForSendResultTimeout(Duration.ofSeconds(3));
        return recoverer;
    }

    private ConsumerRecord<Object, Object> record(String topic, String source) {
        var request = new NotificationCreateRequested(1, source, 7L, NotificationType.LIKE,
            "private-title", "private-body", null, null, Instant.parse("2026-10-22T01:00:00Z"));
        return new ConsumerRecord<>(topic, 0, 4, "7", request);
    }

    private Exception processingFailure() {
        return new TimestampedException(new ListenerExecutionFailedException("private-processing", "current-group",
            new NotificationRetryableCreationException(new TimeoutException("private-cause"))));
    }

    private SendResult<Object, Object> result(String topic, int partition, long offset) {
        return new SendResult<>(new ProducerRecord<>(topic, "7", null),
            new RecordMetadata(new TopicPartition(topic, partition), offset, 0, 0, 0, 0));
    }

    private NotificationFailureContext captured() {
        var context = ArgumentCaptor.forClass(NotificationFailureContext.class);
        verify(reporter).report(context.capture());
        return context.getValue();
    }
}
