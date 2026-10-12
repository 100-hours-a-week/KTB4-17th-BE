package com.team.dating_backend.aisimulation.exception;

import com.team.dating_backend.aisimulation.enums.AiSimulationErrorCode;
import lombok.Getter;

@Getter
public class AiSimulationSubmissionRejectedException extends AiSimulationBusinessException {

    private final Long simulationId;

    public AiSimulationSubmissionRejectedException(Long simulationId, Throwable cause) {
        super(AiSimulationErrorCode.SIMULATION_CAPACITY_EXCEEDED, cause);
        this.simulationId = simulationId;
    }
}
