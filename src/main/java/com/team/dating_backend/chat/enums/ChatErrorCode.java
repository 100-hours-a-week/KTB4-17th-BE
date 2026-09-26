package com.team.dating_backend.chat.enums;

import com.team.dating_backend.common.enums.ErrorCode;
import org.springframework.http.HttpStatus;

public enum ChatErrorCode implements ErrorCode {
    INVALID_CHAT_ROOM_CURSOR(HttpStatus.BAD_REQUEST), INVALID_CHAT_ROOM_PAGE_SIZE(HttpStatus.BAD_REQUEST);

    private final HttpStatus status;

    ChatErrorCode(HttpStatus status) {
        this.status = status;
    }

    @Override
    public HttpStatus status() {
        return status;
    }
}
