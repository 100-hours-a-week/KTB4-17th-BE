package com.team.dating_backend.aisimulation.service;

import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.SESSION_ID;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.CREATED_AT;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.USER_ID;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.TARGET_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.inOrder;

import com.team.dating_backend.aisimulation.dto.internal.AiSimulationExecutionDtos.AdmissionDecision;
import com.team.dating_backend.aisimulation.dto.request.AiSimulationCreateRequest;
import com.team.dating_backend.aisimulation.dto.response.AiSimulationResponses;
import com.team.dating_backend.aisimulation.enums.AiSimulationSessionStatus;
import com.team.dating_backend.aisimulation.enums.AiSimulationErrorCode;
import com.team.dating_backend.aisimulation.exception.AiSimulationBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.core.task.TaskRejectedException;
import com.team.dating_backend.recommendation.service.SimulationRecommendationService;

class AiSimulationServiceTest {
    private AiSimulationAdmissionService admission;
    private AiSimulationWorker worker;
    private AiSimulationPersistenceService persistence;
    private AiSimulationService service;
    private AiSimulationNotificationService notifications;
    private SimulationRecommendationService recommendations;

    @BeforeEach
    void setUp() {
        admission = mock(AiSimulationAdmissionService.class);
        worker = mock(AiSimulationWorker.class);
        persistence = mock(AiSimulationPersistenceService.class);
        notifications = mock(AiSimulationNotificationService.class);
        recommendations = mock(SimulationRecommendationService.class);
        service = new AiSimulationService(admission, worker, persistence, notifications, recommendations);
    }

    @Test
    void 접수_반환_후_새_세션에만_작업을_제출한다() {
        given(admission.admit(USER_ID, TARGET_ID))
            .willReturn(new AdmissionDecision(SESSION_ID, AiSimulationSessionStatus.IN_PROGRESS, true, CREATED_AT));
        assertThat(service.request(USER_ID, new AiSimulationCreateRequest(TARGET_ID)))
            .isEqualTo(new AiSimulationResponses.Handle(SESSION_ID, AiSimulationSessionStatus.IN_PROGRESS));
        var order = inOrder(admission, worker);
        order.verify(admission).admit(USER_ID, TARGET_ID);
        order.verify(worker).generate(SESSION_ID);
        verify(notifications).requested(SESSION_ID, USER_ID, TARGET_ID);
        verify(recommendations).add(TARGET_ID, USER_ID);
        verifyNoInteractions(persistence);
    }

    @ParameterizedTest
    @EnumSource(AiSimulationSessionStatus.class)
    void 기존_세션은_상태와_관계없이_작업을_다시_제출하지_않는다(AiSimulationSessionStatus status) {
        given(admission.admit(USER_ID, TARGET_ID))
            .willReturn(new AdmissionDecision(SESSION_ID, status, false, CREATED_AT));
        assertThat(service.request(USER_ID, new AiSimulationCreateRequest(TARGET_ID)))
            .isEqualTo(new AiSimulationResponses.Handle(SESSION_ID, status));
        verifyNoInteractions(worker, persistence);
        verifyNoInteractions(notifications, recommendations);
    }

    @Test
    void 제출_거절은_실패_저장_후_503_오류로_반환한다() {
        given(admission.admit(USER_ID, TARGET_ID))
            .willReturn(new AdmissionDecision(SESSION_ID, AiSimulationSessionStatus.IN_PROGRESS, true, CREATED_AT));
        doThrow(new TaskRejectedException("Full")).when(worker).generate(SESSION_ID);
        assertThatThrownBy(() -> service.request(USER_ID, new AiSimulationCreateRequest(TARGET_ID)))
            .isInstanceOfSatisfying(AiSimulationBusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(AiSimulationErrorCode.SIMULATION_CAPACITY_EXCEEDED));
        verify(persistence).fail(SESSION_ID);
        verifyNoInteractions(notifications, recommendations);
    }

    @Test
    void 요청_알림이_실패해도_추천을_시도하고_접수는_성공한다() {
        given(admission.admit(USER_ID, TARGET_ID))
            .willReturn(new AdmissionDecision(SESSION_ID, AiSimulationSessionStatus.IN_PROGRESS, true, CREATED_AT));
        doThrow(new IllegalStateException()).when(notifications).requested(SESSION_ID, USER_ID, TARGET_ID);
        assertThat(service.request(USER_ID, new AiSimulationCreateRequest(TARGET_ID)).simulationId())
            .isEqualTo(SESSION_ID);
        verify(recommendations).add(TARGET_ID, USER_ID);
        verifyNoInteractions(persistence);
    }

    @Test
    void 추천이_실패해도_접수된_AI_작업을_실패시키지_않는다() {
        given(admission.admit(USER_ID, TARGET_ID))
            .willReturn(new AdmissionDecision(SESSION_ID, AiSimulationSessionStatus.IN_PROGRESS, true, CREATED_AT));
        doThrow(new IllegalStateException()).when(recommendations).add(TARGET_ID, USER_ID);
        assertThat(service.request(USER_ID, new AiSimulationCreateRequest(TARGET_ID)).simulationId())
            .isEqualTo(SESSION_ID);
        verify(notifications).requested(SESSION_ID, USER_ID, TARGET_ID);
        verifyNoInteractions(persistence);
    }

    @Test
    void 접수_실패는_작업을_제출하지_않는다() {
        given(admission.admit(USER_ID, TARGET_ID))
            .willThrow(new AiSimulationBusinessException(AiSimulationErrorCode.INVALID_TARGET_USER));
        assertThatThrownBy(() -> service.request(USER_ID, new AiSimulationCreateRequest(TARGET_ID)))
            .isInstanceOf(AiSimulationBusinessException.class);
        verifyNoInteractions(worker, persistence);
    }

}
