package com.team.dating_backend.profiletrust.enums;

import com.team.dating_backend.common.enums.ErrorCode;
import org.springframework.http.HttpStatus;

public enum ProfileTrustErrorCode implements ErrorCode {
    VERIFICATION_USER_NOT_FOUND(HttpStatus.NOT_FOUND),
    VERIFICATION_SESSION_NOT_FOUND(HttpStatus.NOT_FOUND),
    VERIFICATION_SESSION_EXPIRED(HttpStatus.GONE),
    VERIFICATION_SESSION_CLOSED(HttpStatus.CONFLICT),
    VERIFICATION_IN_PROGRESS(HttpStatus.CONFLICT),
    VERIFICATION_ATTEMPT_OUT_OF_ORDER(HttpStatus.CONFLICT),
    VERIFICATION_ATTEMPT_CONFLICT(HttpStatus.CONFLICT),
    VERIFICATION_DUPLICATE_VIDEO(HttpStatus.CONFLICT),
    VERIFICATION_ATTEMPT_LIMIT_REACHED(HttpStatus.CONFLICT),
    VERIFICATION_CHALLENGE_INVALID(HttpStatus.GONE),
    VERIFICATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR),
    PRIMARY_PHOTO_NOT_FOUND(HttpStatus.NOT_FOUND),
    PRIMARY_PHOTO_NOT_READY(HttpStatus.CONFLICT),
    PRIMARY_PHOTO_CHANGED(HttpStatus.CONFLICT),
    PRIMARY_PHOTO_EXPIRED(HttpStatus.GONE),
    PRIMARY_PHOTO_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    VIDEO_TYPE_NOT_ALLOWED(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    VIDEO_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE),
    VIDEO_READ_FAILED(HttpStatus.BAD_REQUEST),
    AI_REQUEST_REJECTED(HttpStatus.UNPROCESSABLE_ENTITY),
    AI_SERVER_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE),
    AI_SERVER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    AI_SERVER_RESPONSE_INVALID(HttpStatus.BAD_GATEWAY);

    private final HttpStatus status;

    ProfileTrustErrorCode(HttpStatus status) {
        this.status = status;
    }

    @Override
    public HttpStatus status() {
        return status;
    }
}
