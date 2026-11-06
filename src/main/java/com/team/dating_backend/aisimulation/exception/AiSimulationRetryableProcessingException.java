package com.team.dating_backend.aisimulation.exception;

import com.team.dating_backend.aisimulation.enums.AiSimulationErrorCode;

public class AiSimulationRetryableProcessingException extends AiSimulationBusinessException {

    public AiSimulationRetryableProcessingException(Throwable cause) {
        super(AiSimulationErrorCode.SIMULATION_PROCESSING_FAILED, cause);
    }
}
