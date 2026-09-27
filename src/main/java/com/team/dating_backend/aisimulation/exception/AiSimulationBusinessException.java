package com.team.dating_backend.aisimulation.exception;

import com.team.dating_backend.aisimulation.enums.AiSimulationErrorCode;
import com.team.dating_backend.common.exception.BusinessException;

public class AiSimulationBusinessException extends BusinessException {

    public AiSimulationBusinessException(AiSimulationErrorCode errorCode) {
        super(errorCode);
    }

    public AiSimulationBusinessException(
        AiSimulationErrorCode errorCode,
        Throwable cause) {
        super(errorCode, cause);
    }
}
