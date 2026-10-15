package com.team.dating_backend.profiletrust.enums;

import com.team.dating_backend.common.enums.ErrorCode;
import org.springframework.http.HttpStatus;

public enum ProfileTrustErrorCode implements ErrorCode {
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
