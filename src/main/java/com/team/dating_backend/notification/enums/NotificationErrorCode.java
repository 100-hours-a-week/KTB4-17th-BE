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
    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND),
    NOTIFICATION_STREAM_NOT_READY(HttpStatus.SERVICE_UNAVAILABLE),
    OUTBOX_LOOKUP_FAILED(HttpStatus.INTERNAL_SERVER_ERROR),
    OUTBOX_RESTORE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR),
    OUTBOX_PUBLISH_FAILED(HttpStatus.INTERNAL_SERVER_ERROR),
    OUTBOX_PUBLISH_INTERRUPTED(HttpStatus.INTERNAL_SERVER_ERROR),
    OUTBOX_STATE_UPDATE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    NotificationErrorCode(HttpStatus status) {
        this.status = status;
    }

    @Override
    public HttpStatus status() {
        return status;
    }
}
