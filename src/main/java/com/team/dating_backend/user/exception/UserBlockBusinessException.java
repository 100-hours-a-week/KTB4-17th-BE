package com.team.dating_backend.user.exception;

import com.team.dating_backend.common.enums.ErrorCode;
import com.team.dating_backend.common.exception.BusinessException;

public class UserBlockBusinessException extends BusinessException {

    public UserBlockBusinessException(ErrorCode errorCode) {
        super(errorCode);
    }
}
