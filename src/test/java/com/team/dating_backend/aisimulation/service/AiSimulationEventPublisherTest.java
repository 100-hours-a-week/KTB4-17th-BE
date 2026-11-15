package com.team.dating_backend.aisimulation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.team.dating_backend.aisimulation.config.AiSimulationKafkaProperties;
import com.team.dating_backend.aisimulation.enums.AiSimulationErrorCode;
import com.team.dating_backend.aisimulation.exception.AiSimulationBusinessException;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class AiSimulationEventPublisherTest {

    private final KafkaTemplate<Object, Object> template = mock(KafkaTemplate.class);
    private final AiSimulationKafkaProperties properties = new AiSimulationKafkaProperties();
    private final AiSimulationEventFactory eventFactory = new AiSimulationEventFactory();
    private final AiSimulationEventPublisher publisher = new AiSimulationEventPublisher(template, properties,
        eventFactory);
    private final LocalDateTime savedAt = LocalDateTime.of(2026, 11, 6, 10, 5);

    @Test
    void 요청과_완료를_각_설정_토픽에_세션_ID를_키로_발행한다() {
        properties.setRequestedTopic("test-requested");
        properties.setCompletedTopic("test-completed");
        when(template.send(any(String.class), eq(10L), any()))
            .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        publisher.publishRequested(10L, savedAt);
        publisher.publishCompleted(10L, savedAt);

        verify(template).send("test-requested", 10L, eventFactory.requested(10L, savedAt));
        verify(template).send("test-completed", 10L, eventFactory.completed(10L, savedAt));
    }

    @Test
    void 발행_확인_시간이_초과되면_성공으로_반환하거나_발행을_취소하지_않는다() {
        properties.setPublishResultTimeoutMs(1);
        var pending = new CompletableFuture<SendResult<Object, Object>>();
        when(template.send(any(String.class), eq(10L), any())).thenReturn(pending);

        assertThatThrownBy(() -> publisher.publishRequested(10L, savedAt))
            .isInstanceOfSatisfying(AiSimulationBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(AiSimulationErrorCode.SIMULATION_EVENT_PUBLISH_FAILED))
            .hasCauseInstanceOf(TimeoutException.class);
        assertThat(pending.isCancelled()).isFalse();
    }

    @Test
    void 비동기_발행_실패를_에러_코드로_전달한다() {
        when(template.send(any(String.class), eq(10L), any()))
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException()));

        assertThatThrownBy(() -> publisher.publishCompleted(10L, savedAt))
            .isInstanceOfSatisfying(AiSimulationBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(AiSimulationErrorCode.SIMULATION_EVENT_PUBLISH_FAILED))
            .hasCauseInstanceOf(ExecutionException.class);
    }

    @Test
    void send_호출_자체의_실패도_에러_코드로_전달한다() {
        when(template.send(any(String.class), eq(10L), any())).thenThrow(new IllegalStateException());

        assertThatThrownBy(() -> publisher.publishRequested(10L, savedAt))
            .isInstanceOfSatisfying(AiSimulationBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(AiSimulationErrorCode.SIMULATION_EVENT_PUBLISH_FAILED));
    }

    @Test
    void 발행_확인_대기_중_인터럽트_상태를_보존한다() throws Exception {
        CompletableFuture<SendResult<Object, Object>> future = mock(CompletableFuture.class);
        when(template.send(any(String.class), eq(10L), any())).thenReturn(future);
        when(future.get(properties.getPublishResultTimeoutMs(), TimeUnit.MILLISECONDS))
            .thenThrow(new InterruptedException());

        try {
            assertThatThrownBy(() -> publisher.publishRequested(10L, savedAt))
                .isInstanceOf(AiSimulationBusinessException.class)
                .hasCauseInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void 잘못된_이벤트는_발행_전에_기존_검증_에러로_거절한다() {
        assertThatThrownBy(() -> publisher.publishRequested(null, savedAt))
            .isInstanceOfSatisfying(AiSimulationBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(AiSimulationErrorCode.SIMULATION_EVENT_INVALID));
        verifyNoInteractions(template);
    }
}
