package com.team.dating_backend.aisimulation.service;

import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.CREATED_AT;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.SESSION_ID;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.TARGET_ID;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.team.dating_backend.aisimulation.dto.internal.AiSimulationExecutionDtos.AdmissionDecision;
import com.team.dating_backend.aisimulation.dto.request.AiSimulationCreateRequest;
import com.team.dating_backend.aisimulation.enums.AiSimulationErrorCode;
import com.team.dating_backend.aisimulation.enums.AiSimulationSessionStatus;
import com.team.dating_backend.aisimulation.exception.AiSimulationBusinessException;
import com.team.dating_backend.aisimulation.exception.AiSimulationRequestPublishUnconfirmedException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class AiSimulationServiceTest {

    private final AiSimulationAdmissionService admission = mock(AiSimulationAdmissionService.class);
    private final AiSimulationEventPublisher publisher = mock(AiSimulationEventPublisher.class);
    private final AiSimulationService service = new AiSimulationService(admission, publisher);

    @Test
    void 신규_접수_반환_후_저장된_생성_시각으로_요청_이벤트를_발행한다() {
        var decision = new AdmissionDecision(SESSION_ID, AiSimulationSessionStatus.IN_PROGRESS, true, CREATED_AT);
        given(admission.admit(USER_ID, TARGET_ID)).willReturn(decision);

        assertThat(service.request(USER_ID, new AiSimulationCreateRequest(TARGET_ID))).isSameAs(decision);

        var order = inOrder(admission, publisher);
        order.verify(admission).admit(USER_ID, TARGET_ID);
        order.verify(publisher).publishRequested(SESSION_ID, CREATED_AT);
        order.verifyNoMoreInteractions();
    }

    @ParameterizedTest
    @EnumSource(AiSimulationSessionStatus.class)
    void 기존_세션은_상태와_관계없이_이벤트를_발행하지_않는다(AiSimulationSessionStatus status) {
        var decision = new AdmissionDecision(SESSION_ID, status, false, CREATED_AT);
        given(admission.admit(USER_ID, TARGET_ID)).willReturn(decision);

        assertThat(service.request(USER_ID, new AiSimulationCreateRequest(TARGET_ID))).isSameAs(decision);

        verifyNoInteractions(publisher);
    }

    @Test
    void 발행_확인_실패는_세션_ID와_원인을_보존하는_전용_예외로_전달한다() {
        given(admission.admit(USER_ID, TARGET_ID))
            .willReturn(new AdmissionDecision(SESSION_ID, AiSimulationSessionStatus.IN_PROGRESS, true, CREATED_AT));
        var failure = new AiSimulationBusinessException(AiSimulationErrorCode.SIMULATION_EVENT_PUBLISH_FAILED,
            new TimeoutException());
        doThrow(failure).when(publisher).publishRequested(SESSION_ID, CREATED_AT);

        assertThatThrownBy(() -> service.request(USER_ID, new AiSimulationCreateRequest(TARGET_ID)))
            .isInstanceOfSatisfying(AiSimulationRequestPublishUnconfirmedException.class, exception -> {
                assertThat(exception.getSimulationId()).isEqualTo(SESSION_ID);
                assertThat(exception.getErrorCode())
                    .isEqualTo(AiSimulationErrorCode.SIMULATION_REQUEST_PUBLISH_UNCONFIRMED);
                assertThat(exception.getCause()).isSameAs(failure);
            });
    }

    @Test
    void 발행_전_이벤트_검증_오류는_발행_미확인_오류로_바꾸지_않는다() {
        given(admission.admit(USER_ID, TARGET_ID))
            .willReturn(new AdmissionDecision(SESSION_ID, AiSimulationSessionStatus.IN_PROGRESS, true, CREATED_AT));
        var failure = new AiSimulationBusinessException(AiSimulationErrorCode.SIMULATION_EVENT_INVALID);
        doThrow(failure).when(publisher).publishRequested(SESSION_ID, CREATED_AT);

        assertThatThrownBy(() -> service.request(USER_ID, new AiSimulationCreateRequest(TARGET_ID)))
            .isSameAs(failure);
    }

    @Test
    void 접수_실패는_그대로_전파하고_이벤트를_발행하지_않는다() {
        var failure = new AiSimulationBusinessException(AiSimulationErrorCode.INVALID_TARGET_USER);
        given(admission.admit(USER_ID, TARGET_ID)).willThrow(failure);

        assertThatThrownBy(() -> service.request(USER_ID, new AiSimulationCreateRequest(TARGET_ID)))
            .isSameAs(failure);
        verifyNoInteractions(publisher);
    }
}
