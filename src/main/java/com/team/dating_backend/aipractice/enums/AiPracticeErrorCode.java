package com.team.dating_backend.aipractice.enums;

import com.team.dating_backend.common.enums.ErrorCode;
import org.springframework.http.HttpStatus;

public enum AiPracticeErrorCode implements ErrorCode {
    USER_NOT_FOUND(HttpStatus.NOT_FOUND),
    SESSION_NOT_FOUND(HttpStatus.NOT_FOUND),
    CHAT_NOT_FOUND(
        HttpStatus.NOT_FOUND),
    TARGET_MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND),
    TARGET_MEMBER_UNAVAILABLE(
        HttpStatus.CONFLICT),
    INVALID_TARGET_MEMBER(HttpStatus.BAD_REQUEST),
    SESSION_ENDED(
        HttpStatus.CONFLICT),
    GENERATION_IN_PROGRESS(HttpStatus.CONFLICT),
    CLIENT_MESSAGE_ID_CONFLICT(
        HttpStatus.CONFLICT),
    CHAT_NOT_RETRYABLE(HttpStatus.CONFLICT),
    DAILY_LIMIT_EXCEEDED(
        HttpStatus.TOO_MANY_REQUESTS),
    AI_SESSION_ID_CONFLICT(
        HttpStatus.CONFLICT),
    AI_SERVER_NOT_CONFIGURED(
        HttpStatus.SERVICE_UNAVAILABLE),
    AI_SERVER_RESPONSE_INVALID(
        HttpStatus.BAD_GATEWAY),
    AI_CALLBACK_UNAUTHORIZED(
        HttpStatus.UNAUTHORIZED),
    AI_CALLBACK_INVALID(HttpStatus.BAD_REQUEST);

    private final HttpStatus status;

    AiPracticeErrorCode(HttpStatus status) {
        this.status = status;
    }

    @Override
    public HttpStatus status() {
        return status;
    }
}
