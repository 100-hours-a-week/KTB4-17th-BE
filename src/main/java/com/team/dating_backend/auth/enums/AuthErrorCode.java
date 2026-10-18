package com.team.dating_backend.auth.enums;

import com.team.dating_backend.common.enums.ErrorCode;
import org.springframework.http.HttpStatus;

public enum AuthErrorCode implements ErrorCode {
    OAUTH_LOGIN_USER_ID_MISSING(HttpStatus.UNAUTHORIZED),
    REFRESH_AUTH_TOKEN_MISSING(HttpStatus.UNAUTHORIZED),
    INVALID_REFRESH_AUTH_TOKEN_PURPOSE(HttpStatus.UNAUTHORIZED),
    REFRESH_AUTH_TOKEN_CLAIM_MISSING(HttpStatus.UNAUTHORIZED),
    INVALID_REFRESH_AUTH_TOKEN_CLAIM(HttpStatus.UNAUTHORIZED),
    REFRESH_TOKEN_SESSION_NOT_REGISTERED(HttpStatus.UNAUTHORIZED),
    REFRESH_TOKEN_SESSION_NOT_ACTIVE(HttpStatus.UNAUTHORIZED),
    REFRESH_TOKEN_REUSE_DETECTED(HttpStatus.UNAUTHORIZED),
    REFRESH_TOKEN_USER_NOT_ALLOWED(HttpStatus.UNAUTHORIZED);

    private final HttpStatus status;

    AuthErrorCode(HttpStatus status) {
        this.status = status;
    }

    @Override
    public HttpStatus status() {
        return status;
    }
}
