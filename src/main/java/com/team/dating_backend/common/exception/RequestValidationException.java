package com.team.dating_backend.common.exception;

import com.team.dating_backend.common.enums.CommonErrorCode;

public class RequestValidationException extends BusinessException {

    public RequestValidationException() {
        super(CommonErrorCode.INVALID_REQUEST);
    }

    public RequestValidationException(String message) {
        super(CommonErrorCode.INVALID_REQUEST, message);
    }
}
