package com.team.dating_backend.aisimulation.service;

import com.team.dating_backend.aisimulation.dto.internal.AiSimulationExecutionDtos.AdmissionDecision;
import com.team.dating_backend.aisimulation.dto.request.AiSimulationCreateRequest;
import com.team.dating_backend.aisimulation.dto.response.AiSimulationResponses;
import com.team.dating_backend.aisimulation.enums.AiSimulationErrorCode;
import com.team.dating_backend.aisimulation.exception.AiSimulationBusinessException;
import com.team.dating_backend.aisimulation.exception.AiSimulationSubmissionRejectedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.team.dating_backend.recommendation.service.SimulationRecommendationService;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiSimulationService {
    private final AiSimulationAdmissionService admissionService;
    private final AiSimulationWorker worker;
    private final AiSimulationPersistenceService persistenceService;
    private final AiSimulationNotificationService notificationService;
    private final SimulationRecommendationService recommendationService;

    public AiSimulationResponses.Handle request(Long userId, AiSimulationCreateRequest request) {
        AdmissionDecision decision = admissionService.admit(userId, request.targetUserId());
        if (decision.created()) {
            try {
                worker.generate(decision.simulationId());
            } catch (TaskRejectedException exception) {
                persistenceService.fail(decision.simulationId());
                throw new AiSimulationBusinessException(AiSimulationErrorCode.SIMULATION_CAPACITY_EXCEEDED);
            }
            try {
                notificationService.requested(decision.simulationId(), userId, request.targetUserId());
            } catch (RuntimeException exception) {
                log.warn("Simulation request notification failed: simulationId={}", decision.simulationId(), exception);
            }
            try {
                recommendationService.add(request.targetUserId(), userId);
            } catch (RuntimeException exception) {
                log.warn("Simulation recommendation failed: simulationId={}", decision.simulationId(), exception);
            }
        }
        return new AiSimulationResponses.Handle(decision.simulationId(), decision.status());
    }

    public AiSimulationResponses.Handle attempt(Long userId, Long simulationId) {
        AdmissionDecision decision = admissionService.admitAttempt(userId, simulationId);
        if (decision.created()) {
            try {
                worker.generate(decision.simulationId());
            } catch (TaskRejectedException exception) {
                persistenceService.fail(decision.simulationId());
                throw new AiSimulationSubmissionRejectedException(decision.simulationId(), exception);
            }
        }
        return new AiSimulationResponses.Handle(decision.simulationId(), decision.status());
    }
}
