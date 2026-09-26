package com.team.dating_backend.chat.enums;

import com.team.dating_backend.common.enums.ErrorCode;
import org.springframework.http.HttpStatus;

public enum ChatErrorCode implements ErrorCode {
    INVALID_CHAT_ROOM_CURSOR(HttpStatus.BAD_REQUEST), INVALID_CHAT_ROOM_PAGE_SIZE(
        HttpStatus.BAD_REQUEST), INVALID_CHAT_MESSAGE_CURSOR(HttpStatus.BAD_REQUEST), INVALID_CHAT_MESSAGE_PAGE_SIZE(
            HttpStatus.BAD_REQUEST), CHAT_ACCESS_DENIED(
                HttpStatus.FORBIDDEN), CHAT_ROOM_NOT_FOUND(HttpStatus.NOT_FOUND), CHAT_ROOM_NOT_ACTIVE(
                    HttpStatus.CONFLICT), CLIENT_MESSAGE_ID_CONFLICT(HttpStatus.CONFLICT), TOO_MANY_MESSAGE_REQUESTS(
                        HttpStatus.TOO_MANY_REQUESTS), INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ChatErrorCode(HttpStatus status) {
        this.status = status;
    }

    @Override
    public HttpStatus status() {
        return status;
    }
}
