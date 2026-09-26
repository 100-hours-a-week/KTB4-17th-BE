package com.team.dating_backend.file.enums;

import com.team.dating_backend.common.enums.ErrorCode;
import org.springframework.http.HttpStatus;

public enum FileErrorCode implements ErrorCode {
    FILE_NOT_FOUND(HttpStatus.NOT_FOUND), FILE_UPLOAD_INTENT_NOT_FOUND(
        HttpStatus.NOT_FOUND), FILE_UPLOAD_INTENT_EXPIRED(HttpStatus.GONE), FILE_UPLOAD_INTENT_CONFLICT(
            HttpStatus.CONFLICT), FILE_UPLOAD_NOT_COMPLETE(HttpStatus.CONFLICT), FILE_TOO_LARGE(
                HttpStatus.CONTENT_TOO_LARGE), FILE_TYPE_NOT_ALLOWED(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE), FILE_INVALID_CONTENT(
                        HttpStatus.BAD_REQUEST), FILE_UPLOAD_FAILED(
                            HttpStatus.INTERNAL_SERVER_ERROR), FILE_READ_URL_FAILED(
                                HttpStatus.INTERNAL_SERVER_ERROR), FILE_DELETE_FAILED(
                                    HttpStatus.INTERNAL_SERVER_ERROR), FILE_INVALID_STATE(
                                        HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    FileErrorCode(HttpStatus status) {
        this.status = status;
    }

    @Override
    public HttpStatus status() {
        return status;
    }
}
