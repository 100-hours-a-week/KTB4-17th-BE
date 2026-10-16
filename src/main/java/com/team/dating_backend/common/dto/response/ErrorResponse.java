package com.team.dating_backend.common.dto.response;

import com.team.dating_backend.common.enums.ErrorCode;

public record ErrorResponse(String errorCode) {

    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(errorCode.name());
    }
}
