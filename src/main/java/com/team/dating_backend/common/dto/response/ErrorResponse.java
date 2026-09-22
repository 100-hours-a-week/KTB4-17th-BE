package com.team.dating_backend.common.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.common.enums.ErrorCode;
import java.util.List;

public record ErrorResponse(
        String errorCode,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<FieldErrorResponse> errors) {

    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(errorCode.name(), List.of());
    }

    public static ErrorResponse invalidRequest(List<FieldErrorResponse> errors) {
        return new ErrorResponse(CommonErrorCode.INVALID_REQUEST.name(), List.copyOf(errors));
    }
}
