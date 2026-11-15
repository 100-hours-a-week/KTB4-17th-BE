package com.team.dating_backend.profile.enums;

import com.team.dating_backend.common.enums.ErrorCode;
import org.springframework.http.HttpStatus;

public enum ProfileCacheErrorCode implements ErrorCode {
    PROFILE_CACHE_USER_ID_INVALID(HttpStatus.INTERNAL_SERVER_ERROR),
    PROFILE_CACHE_PROFILE_INVALID(HttpStatus.INTERNAL_SERVER_ERROR),
    PROFILE_CACHE_EXPIRATION_REQUIRED(HttpStatus.INTERNAL_SERVER_ERROR),
    PROFILE_CACHE_LOOKUP_STATUS_REQUIRED(HttpStatus.INTERNAL_SERVER_ERROR),
    PROFILE_CACHE_LOOKUP_RESULT_INVALID(HttpStatus.INTERNAL_SERVER_ERROR),
    PROFILE_CACHE_IMAGE_EXPIRATION_MISSING(HttpStatus.INTERNAL_SERVER_ERROR),
    PROFILE_CACHE_READ_FAILED(HttpStatus.INTERNAL_SERVER_ERROR),
    PROFILE_CACHE_DESERIALIZATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR),
    PROFILE_CACHE_DATA_INVALID(HttpStatus.INTERNAL_SERVER_ERROR),
    PROFILE_CACHE_SERIALIZATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR),
    PROFILE_CACHE_WRITE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR),
    PROFILE_CACHE_DELETE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ProfileCacheErrorCode(HttpStatus status) {
        this.status = status;
    }

    @Override
    public HttpStatus status() {
        return status;
    }
}
