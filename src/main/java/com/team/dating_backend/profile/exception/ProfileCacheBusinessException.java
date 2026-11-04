package com.team.dating_backend.profile.exception;

import com.team.dating_backend.common.exception.BusinessException;
import com.team.dating_backend.profile.enums.ProfileCacheErrorCode;

public class ProfileCacheBusinessException extends BusinessException {

    public ProfileCacheBusinessException(ProfileCacheErrorCode errorCode) {
        super(errorCode);
    }
}
