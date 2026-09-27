package com.team.dating_backend.aisimulation.service;

import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.CREATED_AT;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.SESSION_ID;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.TARGET_ID;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.USER_ID;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.aiResponse;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.publicDetail;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.team.dating_backend.aisimulation.client.AiSimulationAiClient;
import com.team.dating_backend.aisimulation.dto.ai.AiSimulationAiPayloads.SimulationRequest;
import com.team.dating_backend.aisimulation.dto.request.AiSimulationCreateRequest;
import com.team.dating_backend.aisimulation.dto.response.AiSimulationResponses;
import com.team.dating_backend.aisimulation.entity.AiSimulationSession;
import com.team.dating_backend.aisimulation.enums.AiSimulationErrorCode;
import com.team.dating_backend.aisimulation.exception.AiSimulationBusinessException;
import com.team.dating_backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AiSimulationServiceTest {

    private UserRepository userRepository;
    private AiSimulationAiClient aiClient;
    private AiSimulationResponseValidator validator;
    private AiSimulationPersistenceService persistenceService;
    private AiSimulationResponseMapper responseMapper;
    private AiSimulationService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        aiClient = mock(AiSimulationAiClient.class);
        validator = mock(AiSimulationResponseValidator.class);
        persistenceService = mock(AiSimulationPersistenceService.class);
        responseMapper = mock(AiSimulationResponseMapper.class);
        service = new AiSimulationService(
            userRepository, aiClient, validator, persistenceService, responseMapper);
    }

    @Test
    void 자기_자신은_AI를_호출하지_않는다() {
        assertThatThrownBy(() -> service.run(
            USER_ID, new AiSimulationCreateRequest(USER_ID)))
            .isInstanceOfSatisfying(
                AiSimulationBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(AiSimulationErrorCode.INVALID_TARGET_MEMBER));

        verify(aiClient, never()).run(org.mockito.ArgumentMatchers.any());
        verify(persistenceService, never()).begin(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 존재하지_않는_상대는_AI를_호출하지_않는다() {
        given(userRepository.existsById(TARGET_ID)).willReturn(false);

        assertThatThrownBy(() -> service.run(
            USER_ID, new AiSimulationCreateRequest(TARGET_ID)))
            .isInstanceOfSatisfying(
                AiSimulationBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(AiSimulationErrorCode.TARGET_MEMBER_NOT_FOUND));

        verify(aiClient, never()).run(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void AI_결과를_검증하고_완료_저장한_뒤_로컬_ID로_반환한다() {
        AiSimulationSession session = mock(AiSimulationSession.class);
        AiSimulationResponses.Detail expected = publicDetail();
        given(userRepository.existsById(TARGET_ID)).willReturn(true);
        given(persistenceService.begin(USER_ID)).willReturn(session);
        given(session.getId()).willReturn(SESSION_ID);
        given(session.getCreatedAt()).willReturn(CREATED_AT);
        given(aiClient.run(new SimulationRequest("7", "8", 10))).willReturn(aiResponse());
        given(responseMapper.toDetail(
            SESSION_ID, CREATED_AT, aiResponse(), aiResponse().transcript()))
            .willReturn(expected);

        AiSimulationResponses.Detail result = service.run(
            USER_ID, new AiSimulationCreateRequest(TARGET_ID));

        assertThat(result).isEqualTo(expected);
        verify(validator).validate(aiResponse(), USER_ID, TARGET_ID);
        verify(persistenceService).complete(SESSION_ID, aiResponse());
        verify(persistenceService, never()).fail(SESSION_ID);
    }

    @Test
    void AI_호출이_실패하면_세션과_리포트를_실패_처리한다() {
        AiSimulationSession session = mock(AiSimulationSession.class);
        AiSimulationBusinessException failure = new AiSimulationBusinessException(
            AiSimulationErrorCode.SIMULATION_GENERATION_FAILED);
        given(userRepository.existsById(TARGET_ID)).willReturn(true);
        given(persistenceService.begin(USER_ID)).willReturn(session);
        given(session.getId()).willReturn(SESSION_ID);
        given(aiClient.run(new SimulationRequest("7", "8", 10))).willThrow(failure);

        assertThatThrownBy(() -> service.run(
            USER_ID, new AiSimulationCreateRequest(TARGET_ID)))
            .isSameAs(failure);

        verify(persistenceService).fail(SESSION_ID);
        verify(persistenceService, never()).complete(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
