package com.team.dating_backend.persona.kakao.enums;

import com.team.dating_backend.common.enums.ErrorCode;
import org.springframework.http.HttpStatus;

public enum KakaoConversationErrorCode implements ErrorCode {
    KAKAO_FILE_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE),
    KAKAO_FILE_TYPE_NOT_ALLOWED(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    INVALID_KAKAO_EXPORT(HttpStatus.UNPROCESSABLE_CONTENT),
    SPEAKER_SELECTION_REQUIRED(HttpStatus.UNPROCESSABLE_CONTENT),
    SPEAKER_NOT_FOUND(HttpStatus.UNPROCESSABLE_CONTENT),
    SPEAKER_NAME_NOT_SUPPORTED(HttpStatus.UNPROCESSABLE_CONTENT),
    PERSONA_CONFIRMATION_REQUIRED(HttpStatus.CONFLICT),
    KAKAO_JOB_TOKEN_INVALID(HttpStatus.BAD_REQUEST),
    KAKAO_JOB_TOKEN_EXPIRED(HttpStatus.GONE),
    KAKAO_JOB_ACCESS_DENIED(HttpStatus.FORBIDDEN),
    AI_EXTRACTION_IN_PROGRESS(HttpStatus.CONFLICT),
    AI_EXTRACTION_REQUEST_REJECTED(HttpStatus.UNPROCESSABLE_CONTENT),
    AI_EXTRACTION_FAILED(HttpStatus.BAD_GATEWAY),
    AI_JOB_NOT_FOUND(HttpStatus.NOT_FOUND),
    AI_SERVER_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE),
    AI_SERVER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    AI_SERVER_RESPONSE_INVALID(HttpStatus.BAD_GATEWAY),
    AI_SUBMISSION_OUTCOME_UNKNOWN(HttpStatus.SERVICE_UNAVAILABLE);

    private final HttpStatus status;

    KakaoConversationErrorCode(HttpStatus status) {
        this.status = status;
    }

    @Override
    public HttpStatus status() {
        return status;
    }
}
