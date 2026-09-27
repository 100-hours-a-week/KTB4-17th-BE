package com.team.dating_backend.persona.enums;

import com.team.dating_backend.common.enums.ErrorCode;
import org.springframework.http.HttpStatus;

public enum PersonaErrorCode implements ErrorCode {
    PROFILE_NOT_FOUND(HttpStatus.NOT_FOUND),
    PROFILE_NICKNAME_REQUIRED(HttpStatus.CONFLICT),
    PROFILE_MBTI_REQUIRED(HttpStatus.CONFLICT),
    SESSION_NOT_FOUND(HttpStatus.NOT_FOUND),
    PERSONA_DRAFT_NOT_FOUND(HttpStatus.NOT_FOUND),
    NO_PENDING_QUESTION(HttpStatus.CONFLICT),
    REQUEST_IN_PROGRESS(HttpStatus.CONFLICT),
    TURN_MISMATCH(HttpStatus.CONFLICT),
    ACTION_NOT_ALLOWED(HttpStatus.CONFLICT),
    ONBOARDING_NOT_FINISHED(HttpStatus.CONFLICT),
    PERSONA_ALREADY_CONFIRMED(HttpStatus.CONFLICT),
    PERSONA_CONFIRMATION_CONFLICT(HttpStatus.CONFLICT),
    AI_REQUEST_REJECTED(HttpStatus.UNPROCESSABLE_ENTITY),
    AI_SERVER_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE),
    AI_SERVER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    AI_SERVER_RESPONSE_INVALID(HttpStatus.BAD_GATEWAY);

    private final HttpStatus status;

    PersonaErrorCode(HttpStatus status) {
        this.status = status;
    }

    @Override
    public HttpStatus status() {
        return status;
    }
}
