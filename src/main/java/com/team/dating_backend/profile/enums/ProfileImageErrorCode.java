package com.team.dating_backend.profile.enums;

import com.team.dating_backend.common.enums.ErrorCode;
import org.springframework.http.HttpStatus;

public enum ProfileImageErrorCode implements ErrorCode {
    FRONT_PHOTO_REQUIRED(HttpStatus.BAD_REQUEST), FILE_NOT_AVAILABLE(HttpStatus.NOT_FOUND), PROFILE_NOT_FOUND(
        HttpStatus.NOT_FOUND);

    private final HttpStatus status;

    ProfileImageErrorCode(HttpStatus status) {
        this.status = status;
    }

    @Override
    public HttpStatus status() {
        return status;
    }
}
