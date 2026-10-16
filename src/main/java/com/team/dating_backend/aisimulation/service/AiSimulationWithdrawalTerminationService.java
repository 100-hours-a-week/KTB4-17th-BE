package com.team.dating_backend.aisimulation.service;

import com.team.dating_backend.aisimulation.enums.AiSimulationSessionStatus;
import com.team.dating_backend.aisimulation.repository.AiSimulationReportRepository;
import com.team.dating_backend.aisimulation.repository.AiSimulationSessionRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AiSimulationWithdrawalTerminationService {

    private final AiSimulationSessionRepository sessionRepository;
    private final AiSimulationReportRepository reportRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    public void terminateForUser(Long userId, LocalDateTime endedAt) {
        sessionRepository.findAllForUserByStatusForUpdate(
            userId, AiSimulationSessionStatus.IN_PROGRESS)
            .forEach(session -> {
                session.cancel(endedAt);
                reportRepository.findBySessionId(session.getId())
                    .ifPresent(report -> report.fail());
            });
    }
}
