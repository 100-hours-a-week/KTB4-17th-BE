package com.team.dating_backend.matching.exception;

import com.team.dating_backend.common.exception.BusinessException;
import com.team.dating_backend.matching.enums.LikeErrorCode;

public class LikeBusinessException extends BusinessException {

    public LikeBusinessException(LikeErrorCode errorCode) {
        super(errorCode);
    }
}
