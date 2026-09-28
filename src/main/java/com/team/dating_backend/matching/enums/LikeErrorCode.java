package com.team.dating_backend.matching.enums;

import com.team.dating_backend.common.enums.ErrorCode;
import org.springframework.http.HttpStatus;

public enum LikeErrorCode implements ErrorCode {
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND),
    SENDER_NOT_ACTIVE(HttpStatus.FORBIDDEN),
    RECEIVER_NOT_ACTIVE(HttpStatus.FORBIDDEN),
    NOT_LIKE_RECEIVER(HttpStatus.FORBIDDEN),
    LIKE_NOT_FOUND(HttpStatus.NOT_FOUND),
    DUPLICATE_PENDING_LIKE(
        HttpStatus.CONFLICT),
    MATCH_ALREADY_EXISTS(
        HttpStatus.CONFLICT),
    LIKE_ALREADY_RESOLVED(HttpStatus.CONFLICT),
    SELF_LIKE_NOT_ALLOWED(HttpStatus.UNPROCESSABLE_CONTENT);

    private final HttpStatus status;

    LikeErrorCode(HttpStatus status) {
        this.status = status;
    }

    @Override
    public HttpStatus status() {
        return status;
    }
}
