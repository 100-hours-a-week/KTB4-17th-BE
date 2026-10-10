package com.team.dating_backend.user.enums;

import com.team.dating_backend.common.enums.ErrorCode;
import org.springframework.http.HttpStatus;

public enum UserBlockErrorCode implements ErrorCode {
    BLOCK_ACCESS_DENIED(HttpStatus.FORBIDDEN),
    USER_NOT_AVAILABLE(HttpStatus.NOT_FOUND),
    SELF_BLOCK_NOT_ALLOWED(HttpStatus.UNPROCESSABLE_CONTENT);

    private final HttpStatus status;

    UserBlockErrorCode(HttpStatus status) {
        this.status = status;
    }

    @Override
    public HttpStatus status() {
        return status;
    }
}
