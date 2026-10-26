package com.team.dating_backend.chat.enums;

import com.team.dating_backend.common.enums.ErrorCode;
import org.springframework.http.HttpStatus;

public enum ChatErrorCode implements ErrorCode {
    INVALID_CHAT_ROOM_CURSOR(HttpStatus.BAD_REQUEST),
    INVALID_CHAT_ROOM_PAGE_SIZE(
        HttpStatus.BAD_REQUEST),
    INVALID_CHAT_MESSAGE_CURSOR(HttpStatus.BAD_REQUEST),
    INVALID_CHAT_MESSAGE_PAGE_SIZE(
        HttpStatus.BAD_REQUEST),
    CHAT_ACCESS_DENIED(
        HttpStatus.FORBIDDEN),
    CHAT_ROOM_NOT_FOUND(HttpStatus.NOT_FOUND),
    CHAT_IMAGE_NOT_FOUND(HttpStatus.NOT_FOUND),
    CHAT_ROOM_NOT_ACTIVE(
        HttpStatus.CONFLICT),
    INVALID_CHAT_MESSAGE_READ_CURSOR(HttpStatus.BAD_REQUEST),
    CLIENT_MESSAGE_ID_CONFLICT(HttpStatus.CONFLICT),
    TOO_MANY_MESSAGE_REQUESTS(
        HttpStatus.TOO_MANY_REQUESTS),
    CHAT_DELEGATION_ALREADY_EXISTS(HttpStatus.CONFLICT),
    CHAT_DELEGATION_CONVERSATION_REQUIRED(HttpStatus.CONFLICT),
    CHAT_DELEGATION_NOT_FOUND(HttpStatus.NOT_FOUND),
    CHAT_DELEGATION_INVALID_STATUS(HttpStatus.CONFLICT),
    CHAT_DELEGATION_ACTION_NOT_ALLOWED(HttpStatus.CONFLICT),
    CHAT_DELEGATION_DRAFT_MISMATCH(HttpStatus.CONFLICT),
    CHAT_DELEGATION_AI_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE),
    CHAT_DELEGATION_AI_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    CHAT_DELEGATION_AI_REQUEST_REJECTED(HttpStatus.UNPROCESSABLE_ENTITY),
    CHAT_DELEGATION_AI_RESPONSE_INVALID(HttpStatus.BAD_GATEWAY),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ChatErrorCode(HttpStatus status) {
        this.status = status;
    }

    @Override
    public HttpStatus status() {
        return status;
    }
}
