package com.team.dating_backend.aisimulation.service;

import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.SESSION_ID;
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
import com.team.dating_backend.aisimulation.exception.AiSimulationSubmissionRejectedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.core.task.TaskRejectedException;

class AiSimulationServiceTest {
    private AiSimulationAdmissionService admission;
    private AiSimulationWorker worker;
    private AiSimulationPersistenceService persistence;
    private AiSimulationService service;

    @BeforeEach
    void setUp() {
        admission = mock(AiSimulationAdmissionService.class);
        worker = mock(AiSimulationWorker.class);
        persistence = mock(AiSimulationPersistenceService.class);
        service = new AiSimulationService(admission, worker, persistence);
    }

    @Test
    void 접수_반환_후_새_세션에만_작업을_제출한다() {
        given(admission.admit(USER_ID, TARGET_ID))
            .willReturn(new AdmissionDecision(SESSION_ID, AiSimulationSessionStatus.IN_PROGRESS, true));
        assertThat(service.request(USER_ID, new AiSimulationCreateRequest(TARGET_ID)))
            .isEqualTo(new AiSimulationResponses.Handle(SESSION_ID, AiSimulationSessionStatus.IN_PROGRESS));
        var order = inOrder(admission, worker);
        order.verify(admission).admit(USER_ID, TARGET_ID);
        order.verify(worker).generate(SESSION_ID);
        verifyNoInteractions(persistence);
    }

    @ParameterizedTest
    @EnumSource(AiSimulationSessionStatus.class)
    void 기존_세션은_상태와_관계없이_작업을_다시_제출하지_않는다(AiSimulationSessionStatus status) {
        given(admission.admit(USER_ID, TARGET_ID)).willReturn(new AdmissionDecision(SESSION_ID, status, false));
        assertThat(service.request(USER_ID, new AiSimulationCreateRequest(TARGET_ID)))
            .isEqualTo(new AiSimulationResponses.Handle(SESSION_ID, status));
        verifyNoInteractions(worker, persistence);
    }

    @Test
    void 제출_거절은_실패_저장_후_503_오류로_반환한다() {
        given(admission.admit(USER_ID, TARGET_ID))
            .willReturn(new AdmissionDecision(SESSION_ID, AiSimulationSessionStatus.IN_PROGRESS, true));
        doThrow(new TaskRejectedException("Full")).when(worker).generate(SESSION_ID);
        assertThatThrownBy(() -> service.request(USER_ID, new AiSimulationCreateRequest(TARGET_ID)))
            .isInstanceOfSatisfying(AiSimulationBusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(AiSimulationErrorCode.SIMULATION_CAPACITY_EXCEEDED));
        verify(persistence).fail(SESSION_ID);
    }

    @Test
    void 접수_실패는_작업을_제출하지_않는다() {
        given(admission.admit(USER_ID, TARGET_ID))
            .willThrow(new AiSimulationBusinessException(AiSimulationErrorCode.INVALID_TARGET_MEMBER));
        assertThatThrownBy(() -> service.request(USER_ID, new AiSimulationCreateRequest(TARGET_ID)))
            .isInstanceOf(AiSimulationBusinessException.class);
        verifyNoInteractions(worker, persistence);
    }

    @Test
    void 새_시도는_커밋된_새_ID로만_작업을_제출한다() {
        given(admission.admitAttempt(TARGET_ID, SESSION_ID))
            .willReturn(new AdmissionDecision(SESSION_ID + 1, AiSimulationSessionStatus.IN_PROGRESS, true));

        assertThat(service.attempt(TARGET_ID, SESSION_ID))
            .isEqualTo(new AiSimulationResponses.Handle(SESSION_ID + 1, AiSimulationSessionStatus.IN_PROGRESS));
        var order = inOrder(admission, worker);
        order.verify(admission).admitAttempt(TARGET_ID, SESSION_ID);
        order.verify(worker).generate(SESSION_ID + 1);
        verifyNoInteractions(persistence);
    }

    @ParameterizedTest
    @EnumSource(AiSimulationSessionStatus.class)
    void 중복_시도는_최신_ID를_반환하고_작업을_제출하지_않는다(AiSimulationSessionStatus status) {
        given(admission.admitAttempt(USER_ID, SESSION_ID))
            .willReturn(new AdmissionDecision(SESSION_ID + 1, status, false));

        assertThat(service.attempt(USER_ID, SESSION_ID))
            .isEqualTo(new AiSimulationResponses.Handle(SESSION_ID + 1, status));
        verifyNoInteractions(worker, persistence);
    }

    @Test
    void 시도_제출_거절은_새_ID를_포함하고_새_세션만_실패시킨다() {
        given(admission.admitAttempt(USER_ID, SESSION_ID))
            .willReturn(new AdmissionDecision(SESSION_ID + 1, AiSimulationSessionStatus.IN_PROGRESS, true));
        doThrow(new TaskRejectedException("Full")).when(worker).generate(SESSION_ID + 1);

        assertThatThrownBy(() -> service.attempt(USER_ID, SESSION_ID))
            .isInstanceOfSatisfying(AiSimulationSubmissionRejectedException.class, exception -> {
                assertThat(exception.getSimulationId()).isEqualTo(SESSION_ID + 1);
                assertThat(exception.getErrorCode()).isEqualTo(AiSimulationErrorCode.SIMULATION_CAPACITY_EXCEEDED);
            });
        verify(persistence).fail(SESSION_ID + 1);
    }
}
