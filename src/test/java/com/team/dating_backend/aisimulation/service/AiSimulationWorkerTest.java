package com.team.dating_backend.aisimulation.service;

import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.SESSION_ID;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.TARGET_ID;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.USER_ID;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.aiResponse;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.team.dating_backend.aisimulation.client.AiSimulationAiClient;
import com.team.dating_backend.aisimulation.dto.ai.AiSimulationAiPayloads.SimulationRequest;
import com.team.dating_backend.aisimulation.dto.internal.AiSimulationExecutionDtos.GenerationInput;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AiSimulationWorkerTest {

    private AiSimulationPersistenceService persistenceService;
    private AiSimulationAiClient aiClient;
    private AiSimulationResponseValidator validator;
    private AiSimulationWorker worker;

    @BeforeEach
    void setUp() {
        persistenceService = mock(AiSimulationPersistenceService.class);
        aiClient = mock(AiSimulationAiClient.class);
        validator = mock(AiSimulationResponseValidator.class);
        worker = new AiSimulationWorker(persistenceService, aiClient, validator);
    }

    @Test
    void 저장된_ID로_AI를_호출하고_검증_후_완료_저장한다() {
        givenInputAndResponse();

        worker.generate(SESSION_ID);

        var order = inOrder(persistenceService, aiClient, validator);
        order.verify(persistenceService).loadGenerationInput(SESSION_ID);
        order.verify(aiClient).run(request());
        order.verify(validator).validate(aiResponse(), USER_ID, TARGET_ID);
        order.verify(persistenceService).complete(SESSION_ID, aiResponse());
        verify(persistenceService, never()).fail(SESSION_ID);
    }

    @Test
    void 실행할_입력이_없으면_AI_호출이나_상태_변경을_하지_않는다() {
        given(persistenceService.loadGenerationInput(SESSION_ID)).willReturn(Optional.empty());

        worker.generate(SESSION_ID);

        verifyNoInteractions(aiClient, validator);
        verify(persistenceService, never()).complete(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(persistenceService, never()).fail(SESSION_ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"load", "ai", "validate", "complete"})
    void 실행_단계가_실패하면_재시도_없이_실패를_저장한다(String stage) {
        givenInputAndResponse();
        RuntimeException failure = new IllegalStateException("Generation failed");
        switch (stage) {
            case "load" -> given(persistenceService.loadGenerationInput(SESSION_ID)).willThrow(failure);
            case "ai" -> given(aiClient.run(request())).willThrow(failure);
            case "validate" -> doThrow(failure).when(validator)
                .validate(aiResponse(), USER_ID, TARGET_ID);
            case "complete" -> doThrow(failure).when(persistenceService)
                .complete(SESSION_ID, aiResponse());
            default -> throw new IllegalArgumentException(stage);
        }

        assertThatCode(() -> worker.generate(SESSION_ID)).doesNotThrowAnyException();

        verify(persistenceService, times(1)).fail(SESSION_ID);
        verify(aiClient, times(stage.equals("load") ? 0 : 1)).run(request());
        if (!stage.equals("complete")) {
            verify(persistenceService, never()).complete(SESSION_ID, aiResponse());
        }
    }

    @Test
    void 실패_저장까지_실패해도_AI를_다시_호출하지_않는다() {
        givenInputAndResponse();
        given(aiClient.run(request())).willThrow(new IllegalStateException("AI failed"));
        doThrow(new IllegalStateException("Persistence failed"))
            .when(persistenceService).fail(SESSION_ID);

        assertThatCode(() -> worker.generate(SESSION_ID)).doesNotThrowAnyException();

        verify(aiClient, times(1)).run(request());
        verify(persistenceService, times(1)).fail(SESSION_ID);
        verifyNoInteractions(validator);
        verify(persistenceService, never()).complete(SESSION_ID, aiResponse());
    }

    private void givenInputAndResponse() {
        given(persistenceService.loadGenerationInput(SESSION_ID))
            .willReturn(Optional.of(new GenerationInput(SESSION_ID, USER_ID, TARGET_ID)));
        given(aiClient.run(request())).willReturn(aiResponse());
    }

    private SimulationRequest request() {
        return new SimulationRequest(USER_ID.toString(), TARGET_ID.toString(), 10);
    }
}
