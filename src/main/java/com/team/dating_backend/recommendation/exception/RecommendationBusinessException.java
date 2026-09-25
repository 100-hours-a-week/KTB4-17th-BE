package com.team.dating_backend.recommendation.exception;

import com.team.dating_backend.common.exception.BusinessException;
import com.team.dating_backend.recommendation.enums.RecommendationErrorCode;

public class RecommendationBusinessException extends BusinessException {

    public RecommendationBusinessException(RecommendationErrorCode errorCode) {
        super(errorCode);
    }
}
