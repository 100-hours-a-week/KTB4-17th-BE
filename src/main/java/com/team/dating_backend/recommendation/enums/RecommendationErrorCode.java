package com.team.dating_backend.recommendation.enums;

import com.team.dating_backend.common.enums.ErrorCode;
import org.springframework.http.HttpStatus;

public enum RecommendationErrorCode implements ErrorCode {
    REQUESTER_NOT_ACTIVE(HttpStatus.FORBIDDEN);

    private final HttpStatus status;

    RecommendationErrorCode(HttpStatus status) {
        this.status = status;
    }

    @Override
    public HttpStatus status() {
        return status;
    }
}
