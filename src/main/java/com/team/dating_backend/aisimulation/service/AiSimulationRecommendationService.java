package com.team.dating_backend.aisimulation.service;

import com.team.dating_backend.aisimulation.enums.AiSimulationErrorCode;
import com.team.dating_backend.aisimulation.exception.AiSimulationBusinessException;
import com.team.dating_backend.aisimulation.repository.AiSimulationSessionRepository;
import com.team.dating_backend.recommendation.enums.SimulationRecommendationResult;
import com.team.dating_backend.recommendation.service.SimulationRecommendationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AiSimulationRecommendationService {

    private final AiSimulationSessionRepository sessions;
    private final SimulationRecommendationService recommendations;

    @Transactional(propagation = Propagation.NEVER)
    public SimulationRecommendationResult addRequester(Long sessionId) {
        var session = sessions.findById(sessionId)
            .orElseThrow(() -> new AiSimulationBusinessException(AiSimulationErrorCode.SIMULATION_NOT_FOUND));
        if (session.getTargetUserId() == null) {
            throw new AiSimulationBusinessException(AiSimulationErrorCode.SIMULATION_TARGET_USER_MISSING);
        }
        return recommendations.add(session.getTargetUserId(), session.getUserId());
    }
}
