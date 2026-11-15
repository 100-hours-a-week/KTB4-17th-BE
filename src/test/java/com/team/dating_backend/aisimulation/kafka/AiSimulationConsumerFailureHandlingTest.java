package com.team.dating_backend.aisimulation.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.team.dating_backend.aisimulation.dto.event.AiSimulationCompletedEvent;
import com.team.dating_backend.aisimulation.dto.event.AiSimulationRequestedEvent;
import com.team.dating_backend.aisimulation.enums.AiSimulationErrorCode;
import com.team.dating_backend.aisimulation.enums.AiSimulationNotificationRecipient;
import com.team.dating_backend.aisimulation.enums.AiSimulationNotificationResult;
import com.team.dating_backend.aisimulation.exception.AiSimulationBusinessException;
import com.team.dating_backend.aisimulation.exception.AiSimulationRetryableProcessingException;
import com.team.dating_backend.aisimulation.service.AiSimulationGenerationService;
import com.team.dating_backend.aisimulation.service.AiSimulationNotificationProcessor;
import com.team.dating_backend.aisimulation.service.AiSimulationRecommendationService;
import com.team.dating_backend.recommendation.enums.SimulationRecommendationResult;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.errors.AuthorizationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;

class AiSimulationConsumerFailureHandlingTest {

    private static final Long ID = 100L;
    private static final Instant TIME = Instant.parse("2026-11-07T10:05:00Z");
    private static final ConsumerRecord<Long, AiSimulationRequestedEvent> REQUESTED = new ConsumerRecord<>(
        "requested", 3, 42L, ID, new AiSimulationRequestedEvent("ai-simulation:100:requested", ID, TIME));
    private static final ConsumerRecord<Long, AiSimulationCompletedEvent> COMPLETED = new ConsumerRecord<>(
        "completed", 3, 43L, ID,
        new AiSimulationCompletedEvent("ai-simulation:100:completed", ID, TIME.plusSeconds(60)));

    private final AiSimulationFailureClassifier classifier = new AiSimulationFailureClassifier();
    private final AiSimulationGenerationService generation = mock(AiSimulationGenerationService.class);
    private final AiSimulationNotificationProcessor notifications = mock(AiSimulationNotificationProcessor.class);
    private final AiSimulationRecommendationService recommendations = mock(AiSimulationRecommendationService.class);
    private final AiSimulationGenerationConsumer generationConsumer = new AiSimulationGenerationConsumer(generation,
        classifier);
    private final AiSimulationRequestNotificationConsumer requestConsumer = new AiSimulationRequestNotificationConsumer(
        notifications, classifier);
    private final AiSimulationRecommendationConsumer recommendationConsumer = new AiSimulationRecommendationConsumer(
        recommendations, classifier);
    private final AiSimulationCompletedNotificationConsumer completedConsumer = new AiSimulationCompletedNotificationConsumer(
        notifications, classifier);

    @ParameterizedTest
    @MethodSource("retryableCases")
    void 다섯_업무의_일시_오류를_Retry_표식으로_변환하고_원본을_보존한다(Task task, RuntimeException cause) {
        fail(task, cause);

        assertThatThrownBy(() -> consume(task))
            .isInstanceOfSatisfying(AiSimulationRetryableProcessingException.class, failure -> {
                assertThat(failure.getErrorCode()).isEqualTo(AiSimulationErrorCode.SIMULATION_PROCESSING_FAILED);
                assertThat(failure.getCause()).isSameAs(cause);
                assertThat(failure.getMessage()).isNull();
            });
    }

    @ParameterizedTest
    @MethodSource("nonRetryableCases")
    void 영구_오류와_원인_불명확_오류는_원래_예외를_그대로_전달한다(Task task, RuntimeException failure) {
        fail(task, failure);

        assertThatThrownBy(() -> consume(task)).isSameAs(failure);
    }

    @ParameterizedTest
    @EnumSource(Task.class)
    void 이미_분류된_재시도_예외는_중복으로_감싸지_않는다(Task task) {
        var failure = new AiSimulationRetryableProcessingException(new CannotAcquireLockException(null));
        fail(task, failure);

        assertThatThrownBy(() -> consume(task)).isSameAs(failure);
    }

    @ParameterizedTest
    @EnumSource(Task.class)
    void 중첩된_인터럽트는_Retry로_변환하지_않고_중단_상태를_유지한다(Task task) {
        var failure = new AiSimulationBusinessException(AiSimulationErrorCode.SIMULATION_EVENT_PUBLISH_FAILED,
            new InterruptedException());
        fail(task, failure);
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> consume(task)).isSameAs(failure);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @ParameterizedTest
    @EnumSource(Task.class)
    void Error는_Retry로_변환하거나_정상_반환하지_않는다(Task task) {
        var failure = new AssertionError();
        fail(task, failure);

        assertThatThrownBy(() -> consume(task)).isSameAs(failure);
    }

    @ParameterizedTest
    @EnumSource(AiSimulationNotificationResult.class)
    void 알림의_저장_중복_제외_결과는_모두_정상_반환한다(AiSimulationNotificationResult result) {
        when(notifications.requested(ID)).thenReturn(result);
        when(notifications.completed(ID, AiSimulationNotificationRecipient.REQUESTER)).thenReturn(result);
        when(notifications.completed(ID, AiSimulationNotificationRecipient.TARGET)).thenReturn(result);

        requestConsumer.consume(REQUESTED);
        completedConsumer.consumeForRequester(COMPLETED);
        completedConsumer.consumeForTarget(COMPLETED);

        verify(notifications).requested(ID);
        verify(notifications).completed(ID, AiSimulationNotificationRecipient.REQUESTER);
        verify(notifications).completed(ID, AiSimulationNotificationRecipient.TARGET);
    }

    @ParameterizedTest
    @EnumSource(SimulationRecommendationResult.class)
    void 추천의_반영_중복_제외_결과는_모두_정상_반환한다(SimulationRecommendationResult result) {
        when(recommendations.addRequester(ID)).thenReturn(result);

        recommendationConsumer.consume(REQUESTED);

        verify(recommendations).addRequester(ID);
    }

    @Test
    void 생성_업무는_서비스_처리가_끝난_후_정상_반환한다() {
        generationConsumer.consume(REQUESTED);

        verify(generation).generate(ID);
    }

    private void fail(Task task, Throwable failure) {
        switch (task) {
            case GENERATION -> doThrow(failure).when(generation).generate(ID);
            case REQUEST_NOTIFICATION -> doThrow(failure).when(notifications).requested(ID);
            case RECOMMENDATION -> doThrow(failure).when(recommendations).addRequester(ID);
            case REQUESTER_NOTIFICATION -> doThrow(failure).when(notifications)
                .completed(ID, AiSimulationNotificationRecipient.REQUESTER);
            case TARGET_NOTIFICATION -> doThrow(failure).when(notifications)
                .completed(ID, AiSimulationNotificationRecipient.TARGET);
        }
    }

    private void consume(Task task) {
        switch (task) {
            case GENERATION -> generationConsumer.consume(REQUESTED);
            case REQUEST_NOTIFICATION -> requestConsumer.consume(REQUESTED);
            case RECOMMENDATION -> recommendationConsumer.consume(REQUESTED);
            case REQUESTER_NOTIFICATION -> completedConsumer.consumeForRequester(COMPLETED);
            case TARGET_NOTIFICATION -> completedConsumer.consumeForTarget(COMPLETED);
        }
    }

    private static Stream<Arguments> retryableCases() {
        return Arrays.stream(Task.values()).flatMap(task -> Stream.of(
            new CannotAcquireLockException(null),
            new AiSimulationBusinessException(AiSimulationErrorCode.AI_SERVER_UNAVAILABLE),
            new AiSimulationBusinessException(AiSimulationErrorCode.SIMULATION_EVENT_PUBLISH_FAILED,
                new TimeoutException()))
            .map(failure -> Arguments.of(task, failure)));
    }

    private static Stream<Arguments> nonRetryableCases() {
        return Arrays.stream(Task.values()).flatMap(task -> Stream.of(
            new AiSimulationBusinessException(AiSimulationErrorCode.AI_REQUEST_REJECTED),
            new DataIntegrityViolationException(null), new IllegalStateException(),
            new org.apache.kafka.common.errors.TimeoutException(new AuthorizationException("")))
            .map(failure -> Arguments.of(task, failure)));
    }

    enum Task {
        GENERATION,
        REQUEST_NOTIFICATION,
        RECOMMENDATION,
        REQUESTER_NOTIFICATION,
        TARGET_NOTIFICATION
    }
}
