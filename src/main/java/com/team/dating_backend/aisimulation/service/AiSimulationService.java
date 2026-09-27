package com.team.dating_backend.aisimulation.service;

import com.team.dating_backend.aisimulation.client.AiSimulationAiClient;
import com.team.dating_backend.aisimulation.dto.ai.AiSimulationAiPayloads.SimulationRequest;
import com.team.dating_backend.aisimulation.dto.ai.AiSimulationAiPayloads.SimulationResponse;
import com.team.dating_backend.aisimulation.dto.request.AiSimulationCreateRequest;
import com.team.dating_backend.aisimulation.dto.response.AiSimulationResponses;
import com.team.dating_backend.aisimulation.entity.AiSimulationSession;
import com.team.dating_backend.aisimulation.enums.AiSimulationErrorCode;
import com.team.dating_backend.aisimulation.exception.AiSimulationBusinessException;
import com.team.dating_backend.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AiSimulationService {

    private static final int DEFAULT_TURNS = 10;

    private final UserRepository userRepository;
    private final AiSimulationAiClient aiClient;
    private final AiSimulationResponseValidator validator;
    private final AiSimulationPersistenceService persistenceService;
    private final AiSimulationResponseMapper responseMapper;

    public AiSimulationResponses.Detail run(
        Long userId,
        AiSimulationCreateRequest request) {
        Long targetMemberId = request.targetMemberId();
        validateTarget(userId, targetMemberId);

        AiSimulationSession session = persistenceService.begin(userId);
        try {
            SimulationResponse response = aiClient.run(new SimulationRequest(
                userId.toString(), targetMemberId.toString(), DEFAULT_TURNS));
            validator.validate(response, userId, targetMemberId);
            persistenceService.complete(session.getId(), response);
            return responseMapper.toDetail(
                session.getId(), session.getCreatedAt(), response, response.transcript());
        } catch (RuntimeException exception) {
            try {
                persistenceService.fail(session.getId());
            } catch (RuntimeException persistenceException) {
                exception.addSuppressed(persistenceException);
            }
            throw exception;
        }
    }

    private void validateTarget(Long userId, Long targetMemberId) {
        if (userId.equals(targetMemberId)) {
            throw new AiSimulationBusinessException(
                AiSimulationErrorCode.INVALID_TARGET_MEMBER);
        }
        if (!userRepository.existsById(targetMemberId)) {
            throw new AiSimulationBusinessException(
                AiSimulationErrorCode.TARGET_MEMBER_NOT_FOUND);
        }
    }
}
