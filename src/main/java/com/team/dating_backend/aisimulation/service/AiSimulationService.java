package com.team.dating_backend.aisimulation.service;

import com.team.dating_backend.aisimulation.dto.internal.AiSimulationExecutionDtos.AdmissionDecision;
import com.team.dating_backend.aisimulation.dto.request.AiSimulationCreateRequest;
import com.team.dating_backend.aisimulation.enums.AiSimulationErrorCode;
import com.team.dating_backend.aisimulation.exception.AiSimulationBusinessException;
import com.team.dating_backend.aisimulation.exception.AiSimulationRequestPublishUnconfirmedException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AiSimulationService {
    private final AiSimulationAdmissionService admissionService;
    private final AiSimulationEventPublisher eventPublisher;

    public AdmissionDecision request(Long userId, AiSimulationCreateRequest request) {
        AdmissionDecision decision = admissionService.admit(userId, request.targetUserId());
        if (decision.created()) {
            try {
                eventPublisher.publishRequested(decision.simulationId(), decision.createdAt());
            } catch (AiSimulationBusinessException exception) {
                if (exception.getErrorCode() == AiSimulationErrorCode.SIMULATION_EVENT_PUBLISH_FAILED) {
                    throw new AiSimulationRequestPublishUnconfirmedException(decision.simulationId(), exception);
                }
                throw exception;
            }
        }
        return decision;
    }
}
