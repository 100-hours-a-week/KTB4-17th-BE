package com.team.dating_backend.aisimulation.enums;

import com.team.dating_backend.common.enums.ErrorCode;
import org.springframework.http.HttpStatus;

public enum AiSimulationErrorCode implements ErrorCode {
    TARGET_MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND),
    INVALID_TARGET_MEMBER(HttpStatus.BAD_REQUEST),
    SIMULATION_NOT_FOUND(HttpStatus.NOT_FOUND),
    ME_PERSONA_NOT_FOUND(HttpStatus.NOT_FOUND),
    TARGET_PERSONA_NOT_FOUND(HttpStatus.NOT_FOUND),
    SIMULATION_ALREADY_RUNNING(HttpStatus.CONFLICT),
    AI_REQUEST_REJECTED(HttpStatus.UNPROCESSABLE_ENTITY),
    AI_SERVER_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE),
    AI_SERVER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    SIMULATION_GENERATION_FAILED(HttpStatus.SERVICE_UNAVAILABLE),
    AI_SERVER_RESPONSE_INVALID(HttpStatus.BAD_GATEWAY);

    private final HttpStatus status;

    AiSimulationErrorCode(HttpStatus status) {
        this.status = status;
    }

    @Override
    public HttpStatus status() {
        return status;
    }
}
