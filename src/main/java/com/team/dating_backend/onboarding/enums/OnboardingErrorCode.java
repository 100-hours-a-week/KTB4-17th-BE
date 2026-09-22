package com.team.dating_backend.onboarding.enums;

import com.team.dating_backend.common.enums.ErrorCode;
import org.springframework.http.HttpStatus;

public enum OnboardingErrorCode implements ErrorCode {
    USER_NOT_FOUND(HttpStatus.NOT_FOUND),
    ONBOARDING_ACCESS_NOT_ALLOWED(HttpStatus.FORBIDDEN);

    private final HttpStatus status;

    OnboardingErrorCode(HttpStatus status) {
        this.status = status;
    }

    @Override
    public HttpStatus status() {
        return status;
    }
}
