package com.team.dating_backend.common.exception;

import com.team.dating_backend.common.dto.response.FieldErrorResponse;
import com.team.dating_backend.common.enums.CommonErrorCode;
import java.util.List;

public class RequestValidationException extends BusinessException {

    private final List<FieldErrorResponse> errors;

    public RequestValidationException(List<FieldErrorResponse> errors) {
        super(CommonErrorCode.INVALID_REQUEST);
        this.errors = List.copyOf(errors);
    }

    public List<FieldErrorResponse> getErrors() {
        return errors;
    }
}
