package com.team.dating_backend.common.dto.response;

import java.util.List;

public record ErrorResponse(String errorCode, List<FieldErrorResponse> errors) {

    public static ErrorResponse invalidRequest(String field, String reason) {
        return new ErrorResponse(
                "INVALID_REQUEST",
                List.of(new FieldErrorResponse(field, reason)));
    }
}
