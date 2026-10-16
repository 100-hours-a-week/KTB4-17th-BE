package com.team.dating_backend.recommendation.enums;

import com.team.dating_backend.common.enums.ErrorCode;
import org.springframework.http.HttpStatus;

public enum RecommendationPreferenceErrorCode implements ErrorCode {
    PREFERENCE_AGE_OUT_OF_RANGE(HttpStatus.BAD_REQUEST),
    PREFERENCE_HEIGHT_OUT_OF_RANGE(HttpStatus.BAD_REQUEST),
    PREFERENCE_AGE_RANGE_INVALID(HttpStatus.BAD_REQUEST),
    PREFERENCE_HEIGHT_RANGE_INVALID(HttpStatus.BAD_REQUEST),
    PREFERENCE_RELIGION_NULL_ELEMENT(HttpStatus.BAD_REQUEST),
    PREFERENCE_RELIGION_DUPLICATE_VALUE(HttpStatus.BAD_REQUEST),
    PREFERENCE_DRINKING_NULL_ELEMENT(HttpStatus.BAD_REQUEST),
    PREFERENCE_DRINKING_DUPLICATE_VALUE(HttpStatus.BAD_REQUEST),
    PREFERENCE_SMOKING_NULL_ELEMENT(HttpStatus.BAD_REQUEST),
    PREFERENCE_SMOKING_DUPLICATE_VALUE(HttpStatus.BAD_REQUEST),
    REQUESTER_NOT_ACTIVE(HttpStatus.FORBIDDEN);

    private final HttpStatus status;

    RecommendationPreferenceErrorCode(HttpStatus status) {
        this.status = status;
    }

    @Override
    public HttpStatus status() {
        return status;
    }
}
