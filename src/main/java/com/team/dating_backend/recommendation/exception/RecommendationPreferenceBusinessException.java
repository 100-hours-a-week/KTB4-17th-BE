package com.team.dating_backend.recommendation.exception;

import com.team.dating_backend.common.exception.BusinessException;
import com.team.dating_backend.recommendation.enums.RecommendationPreferenceErrorCode;

public class RecommendationPreferenceBusinessException extends BusinessException {

    public RecommendationPreferenceBusinessException(RecommendationPreferenceErrorCode errorCode) {
        super(errorCode);
    }
}
