package com.team.dating_backend.aisimulation.dto.internal;

import com.team.dating_backend.aisimulation.enums.AiSimulationSessionStatus;
import java.time.LocalDateTime;

public final class AiSimulationExecutionDtos {

    private AiSimulationExecutionDtos() {}

    public record AdmissionDecision(
        Long simulationId,
        AiSimulationSessionStatus status,
        boolean created) {}

    public record GenerationInput(Long simulationId, Long userId, Long targetUserId) {}

    public record GenerationState(
        Long simulationId,
        Long userId,
        Long targetUserId,
        AiSimulationSessionStatus status,
        LocalDateTime finishedAt) {}
}
