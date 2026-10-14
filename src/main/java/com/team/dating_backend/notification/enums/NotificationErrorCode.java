package com.team.dating_backend.notification.enums;

import com.team.dating_backend.common.enums.ErrorCode;
import org.springframework.http.HttpStatus;

public enum NotificationErrorCode implements ErrorCode {
    INVALID_NOTIFICATION_ID(HttpStatus.BAD_REQUEST),
    INVALID_NOTIFICATION_CATEGORY(HttpStatus.BAD_REQUEST),
    INVALID_NOTIFICATION_PAGE_SIZE(HttpStatus.BAD_REQUEST),
    INVALID_NOTIFICATION_CURSOR(HttpStatus.BAD_REQUEST),
    REQUESTER_NOT_ACTIVE(HttpStatus.FORBIDDEN),
    NOTIFICATION_ACCESS_DENIED(HttpStatus.FORBIDDEN),
    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND);

    private final HttpStatus status;

    NotificationErrorCode(HttpStatus status) {
        this.status = status;
    }

    @Override
    public HttpStatus status() {
        return status;
    }
}
