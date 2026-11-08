package com.team.dating_backend.aisimulation.exception;

import com.team.dating_backend.aisimulation.enums.AiSimulationErrorCode;
import lombok.Getter;

@Getter
public class AiSimulationRequestPublishUnconfirmedException extends AiSimulationBusinessException {

    private final Long simulationId;

    public AiSimulationRequestPublishUnconfirmedException(Long simulationId, Throwable cause) {
        super(AiSimulationErrorCode.SIMULATION_REQUEST_PUBLISH_UNCONFIRMED, cause);
        this.simulationId = simulationId;
    }
}
