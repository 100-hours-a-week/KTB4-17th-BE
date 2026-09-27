package com.team.dating_backend.profile.exception;

import com.team.dating_backend.common.exception.BusinessException;
import com.team.dating_backend.profile.enums.ProfileImageErrorCode;

public class ProfileImageBusinessException extends BusinessException {

    public ProfileImageBusinessException(ProfileImageErrorCode errorCode) {
        super(errorCode);
    }
}
