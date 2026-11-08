package com.team.dating_backend.aisimulation.service;

import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.CREATED_AT;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.SESSION_ID;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.TARGET_ID;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.USER_ID;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.aiResponse;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.team.dating_backend.aisimulation.client.AiSimulationAiClient;
import com.team.dating_backend.aisimulation.dto.ai.AiSimulationAiPayloads.SimulationRequest;
import com.team.dating_backend.aisimulation.dto.internal.AiSimulationExecutionDtos.GenerationState;
import com.team.dating_backend.aisimulation.enums.AiSimulationErrorCode;
import com.team.dating_backend.aisimulation.enums.AiSimulationSessionStatus;
import com.team.dating_backend.aisimulation.exception.AiSimulationBusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class AiSimulationGenerationServiceTest {

    private final AiSimulationPersistenceService persistence = mock(AiSimulationPersistenceService.class);
    private final AiSimulationAiClient ai = mock(AiSimulationAiClient.class);
    private final AiSimulationResponseValidator validator = mock(AiSimulationResponseValidator.class);
    private final AiSimulationEventPublisher publisher = mock(AiSimulationEventPublisher.class);
    private final AiSimulationGenerationService service = new AiSimulationGenerationService(
        persistence, ai, validator, publisher);
    private final SimulationRequest request = new SimulationRequest(USER_ID.toString(), TARGET_ID.toString(), 10);

    @Test
    void 생성과_검증과_저장을_끝낸_후_저장된_완료_시각으로_발행한다() {
        givenGeneration();

        service.generate(SESSION_ID);

        var order = inOrder(persistence, ai, validator, publisher);
        order.verify(persistence).loadGenerationState(SESSION_ID);
        order.verify(ai).run(request);
        order.verify(validator).validate(aiResponse(), USER_ID, TARGET_ID);
        order.verify(persistence).completeGeneration(SESSION_ID, aiResponse());
        order.verify(publisher).publishCompleted(SESSION_ID, CREATED_AT.plusMinutes(1));
        verify(persistence, never()).fail(SESSION_ID);
    }

    @Test
    void 완료된_세션은_AI와_저장을_생략하고_완료_이벤트만_발행한다() {
        when(persistence.loadGenerationState(SESSION_ID)).thenReturn(state(AiSimulationSessionStatus.COMPLETED));

        service.generate(SESSION_ID);

        verifyNoInteractions(ai, validator);
        verify(persistence, never()).completeGeneration(org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
        verify(publisher).publishCompleted(SESSION_ID, CREATED_AT.plusMinutes(1));
    }

    @ParameterizedTest
    @EnumSource(value = AiSimulationSessionStatus.class, names = {"FAILED", "CANCELED"})
    void 실패하거나_취소된_세션은_생성과_완료_발행을_생략한다(AiSimulationSessionStatus status) {
        when(persistence.loadGenerationState(SESSION_ID)).thenReturn(state(status));

        service.generate(SESSION_ID);

        verifyNoInteractions(ai, validator, publisher);
        verify(persistence, never()).completeGeneration(org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
    }

    @ParameterizedTest
    @EnumSource(value = AiSimulationSessionStatus.class, names = {"FAILED", "CANCELED"})
    void AI_호출_중_종료된_세션은_저장_결과를_확인하고_완료_발행을_생략한다(AiSimulationSessionStatus status) {
        givenGeneration();
        when(persistence.completeGeneration(SESSION_ID, aiResponse())).thenReturn(state(status));

        service.generate(SESSION_ID);

        verify(validator).validate(aiResponse(), USER_ID, TARGET_ID);
        verifyNoInteractions(publisher);
        verify(persistence, never()).fail(SESSION_ID);
    }

    @Test
    void 다른_처리가_먼저_완료했다면_그_처리가_저장한_완료_시각으로_발행한다() {
        givenGeneration();
        var savedAt = CREATED_AT.minusMinutes(1);
        when(persistence.completeGeneration(SESSION_ID, aiResponse())).thenReturn(new GenerationState(
            SESSION_ID, USER_ID, TARGET_ID, AiSimulationSessionStatus.COMPLETED, savedAt));

        service.generate(SESSION_ID);

        verify(publisher).publishCompleted(SESSION_ID, savedAt);
    }

    @ParameterizedTest
    @ValueSource(strings = {"load", "ai", "validate", "complete", "publish"})
    void 단계별_오류를_그대로_전달하고_즉시_실패_저장을_하지_않는다(String stage) {
        givenGeneration();
        var failure = new AiSimulationBusinessException(AiSimulationErrorCode.SIMULATION_PROCESSING_FAILED);
        switch (stage) {
            case "load" -> when(persistence.loadGenerationState(SESSION_ID)).thenThrow(failure);
            case "ai" -> when(ai.run(request)).thenThrow(failure);
            case "validate" -> doThrow(failure).when(validator).validate(aiResponse(), USER_ID, TARGET_ID);
            case "complete" -> when(persistence.completeGeneration(SESSION_ID, aiResponse())).thenThrow(failure);
            case "publish" -> doThrow(failure).when(publisher).publishCompleted(SESSION_ID, CREATED_AT.plusMinutes(1));
            default -> throw new IllegalArgumentException(stage);
        }

        assertThatThrownBy(() -> service.generate(SESSION_ID)).isSameAs(failure);

        verify(persistence, never()).fail(SESSION_ID);
        if (!stage.equals("publish")) {
            verifyNoInteractions(publisher);
        }
        if (!stage.equals("publish") && !stage.equals("complete")) {
            verify(persistence, never()).completeGeneration(SESSION_ID, aiResponse());
        }
    }

    private void givenGeneration() {
        when(persistence.loadGenerationState(SESSION_ID)).thenReturn(state(AiSimulationSessionStatus.IN_PROGRESS));
        when(ai.run(request)).thenReturn(aiResponse());
        when(persistence.completeGeneration(SESSION_ID, aiResponse()))
            .thenReturn(state(AiSimulationSessionStatus.COMPLETED));
    }

    private GenerationState state(AiSimulationSessionStatus status) {
        return new GenerationState(SESSION_ID, USER_ID, TARGET_ID, status,
            status == AiSimulationSessionStatus.IN_PROGRESS ? null : CREATED_AT.plusMinutes(1));
    }
}
