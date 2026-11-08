package com.team.dating_backend.aisimulation.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.team.dating_backend.aisimulation.config.AiSimulationKafkaProperties;
import com.team.dating_backend.aisimulation.dto.event.AiSimulationCompletedEvent;
import com.team.dating_backend.aisimulation.dto.event.AiSimulationRequestedEvent;
import com.team.dating_backend.aisimulation.service.AiSimulationPersistenceService;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.support.SendResult;

@Timeout(10)
class AiSimulationRecoveryHandlerTest {

    private static final String REQUESTED = "local-ai-simulation-requested";
    private static final String DLT = REQUESTED + "-generation-dlt";
    private static final Long ID = 100L;
    private static final Instant TIME = Instant.parse("2026-11-08T10:05:00Z");
    private static final AiSimulationRequestedEvent EVENT = new AiSimulationRequestedEvent(
        "ai-simulation:100:requested", ID, TIME);

    private final AiSimulationPersistenceService persistence = mock(AiSimulationPersistenceService.class);
    private final KafkaOperations<Object, Object> template = mock(KafkaOperations.class);
    private final AiSimulationRecoveryHandler handler = new AiSimulationRecoveryHandler(persistence,
        new AiSimulationKafkaProperties());

    @Test
    void DLT_전달_확인과_최종_상태_저장이_끝나기_전에는_복구를_완료하지_않는다() throws Exception {
        var pending = new CompletableFuture<SendResult<Object, Object>>();
        when(template.send(any(ProducerRecord.class))).thenReturn(pending);
        var writing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            writing.countDown();
            assertThat(release.await(3, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(persistence).fail(ID);
        var recoverer = recoverer(DLT);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var task = executor
                .submit(() -> recoverer.accept(record(REQUESTED, ID, EVENT), new IllegalStateException()));
            try {
                await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> verify(template).send(any(ProducerRecord.class)));
                verifyNoInteractions(persistence);
                assertThat(task.isDone()).isFalse();
                pending.complete(result(DLT));
                assertThat(writing.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(task.isDone()).isFalse();
            } finally {
                release.countDown();
            }
            task.get(2, TimeUnit.SECONDS);
        }
        verify(persistence).fail(ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "-generation-retry"})
    void 생성_원본과_Retry에서_확정한_DLT만_최종_실패를_처리한다(String suffix) {
        when(template.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(result(DLT)));

        recoverer(DLT).accept(record(REQUESTED + suffix, ID, EVENT), new IllegalStateException());

        verify(persistence).fail(ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"failed", "timeout", "sync"})
    void DLT_발행_실패와_시간_초과는_상태를_변경하지_않고_예외를_전파한다(String failure) {
        var pending = new CompletableFuture<SendResult<Object, Object>>();
        if (failure.equals("sync")) {
            when(template.send(any(ProducerRecord.class))).thenThrow(new IllegalStateException());
        } else {
            when(template.send(any(ProducerRecord.class))).thenReturn(pending);
            if (failure.equals("failed")) {
                pending.completeExceptionally(new java.util.concurrent.TimeoutException());
            }
        }
        var recoverer = recoverer(DLT);
        recoverer.setWaitForSendResultTimeout(Duration.ofMillis(20));

        assertThatThrownBy(() -> recoverer.accept(record(REQUESTED, ID, EVENT), new IllegalStateException()))
            .isInstanceOf(KafkaException.class);
        pending.complete(result(DLT));
        verifyNoInteractions(persistence);
    }

    @Test
    void DLT_확인을_기다리다_인터럽트되면_실패_상태를_저장하지_않고_플래그를_유지한다() {
        when(template.send(any(ProducerRecord.class))).thenReturn(new CompletableFuture<>());
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> recoverer(DLT).accept(record(REQUESTED, ID, EVENT), new IllegalStateException()))
                .isInstanceOf(KafkaException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verifyNoInteractions(persistence);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void DLT_성공_이후_DB_오류는_정상_복구로_삼키지_않는다() {
        var failure = new DataAccessResourceFailureException(null);
        when(template.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(result(DLT)));
        doThrow(failure).when(persistence).fail(ID);

        assertThatThrownBy(() -> recoverer(DLT).accept(record(REQUESTED, ID, EVENT), new IllegalStateException()))
            .isSameAs(failure);
    }

    @ParameterizedTest
    @MethodSource("otherDestinations")
    void Retry_전달과_다른_업무의_DLT는_생성_상태를_변경하지_않는다(String destination) {
        when(template.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.completedFuture(result(destination)));

        recoverer(destination).accept(record(REQUESTED, ID, EVENT), new IllegalStateException());

        verifyNoInteractions(persistence);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8})
    void 식별할_수_없는_사건으로_다른_세션을_실패시키지_않는다(int scenario) {
        Object value = switch (scenario) {
            case 0 -> null;
            case 1 -> new byte[]{1, 2};
            case 2 -> new AiSimulationRequestedEvent(EVENT.eventId(), null, TIME);
            case 3 -> new AiSimulationRequestedEvent("ai-simulation:-1:requested", -1L, TIME);
            case 4 -> new AiSimulationRequestedEvent("different", ID, TIME);
            case 5 -> new AiSimulationRequestedEvent(EVENT.eventId(), ID, null);
            case 7 -> new AiSimulationCompletedEvent("ai-simulation:100:completed", ID, TIME);
            default -> EVENT;
        };
        String topic = scenario == 8 ? REQUESTED + "-request-notification-retry" : REQUESTED;
        when(template.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(result(DLT)));

        recoverer(DLT).accept(record(topic, scenario == 6 ? 200L : ID, value), new IllegalStateException());

        verifyNoInteractions(persistence);
    }

    private DeadLetterPublishingRecoverer recoverer(String destination) {
        var factory = mock(ProducerFactory.class);
        when(factory.getConfigurationProperties()).thenReturn(Map.of(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 1));
        when(template.getProducerFactory()).thenReturn(factory);
        var recoverer = handler.createRecoverer(record -> template,
            (record, exception) -> new TopicPartition(destination, 3));
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setVerifyPartition(false);
        recoverer.setTimeoutBuffer(0);
        recoverer.setWaitForSendResultTimeout(Duration.ofSeconds(3));
        return recoverer;
    }

    private ConsumerRecord<Object, Object> record(String topic, Object key, Object value) {
        return new ConsumerRecord<>(topic, 3, 42, key, value);
    }

    private SendResult<Object, Object> result(String topic) {
        return new SendResult<>(new ProducerRecord<>(topic, 3, ID, EVENT),
            new RecordMetadata(new TopicPartition(topic, 3), 99, 0, 0, 0, 0));
    }

    private static Stream<String> otherDestinations() {
        return Stream.of(REQUESTED + "-generation-retry", REQUESTED + "-request-notification-dlt",
            REQUESTED + "-recommendation-dlt", "local-ai-simulation-completed-requester-notification-dlt",
            "local-ai-simulation-completed-target-notification-dlt");
    }
}
