package com.team.dating_backend.aisimulation.service;

import com.team.dating_backend.aisimulation.dto.internal.AiSimulationExecutionDtos.AdmissionDecision;
import com.team.dating_backend.aisimulation.entity.AiSimulationSession;
import com.team.dating_backend.aisimulation.repository.AiSimulationSessionRepository;
import com.team.dating_backend.common.lock.repository.UserPairLockRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AiSimulationAdmissionService {

    private final AiSimulationTargetValidator targetValidator;
    private final UserPairLockRepository userPairLockRepository;
    private final AiSimulationSessionRepository sessionRepository;
    private final AiSimulationPersistenceService persistenceService;

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public AdmissionDecision admit(Long userId, Long targetUserId) {
        targetValidator.validate(userId, targetUserId);

        Long lowerUserId = Math.min(userId, targetUserId);
        Long higherUserId = Math.max(userId, targetUserId);
        userPairLockRepository.acquire(lowerUserId, higherUserId);

        List<AiSimulationSession> sessions = sessionRepository.findForReuseByUserPair(
            userId, targetUserId, PageRequest.of(0, 1));
        if (!sessions.isEmpty()) {
            return decision(sessions.getFirst(), false);
        }

        AiSimulationSession session = persistenceService.begin(userId, targetUserId);
        return decision(session, true);
    }

    private AdmissionDecision decision(AiSimulationSession session, boolean created) {
        return new AdmissionDecision(session.getId(), session.getStatus(), created);
    }
}
