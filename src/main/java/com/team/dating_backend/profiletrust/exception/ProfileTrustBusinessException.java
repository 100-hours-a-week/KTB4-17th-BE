package com.team.dating_backend.profiletrust.exception;

import com.team.dating_backend.common.exception.BusinessException;
import com.team.dating_backend.profiletrust.enums.ProfileTrustErrorCode;

public class ProfileTrustBusinessException extends BusinessException {

    public ProfileTrustBusinessException(ProfileTrustErrorCode errorCode) {
        super(errorCode);
    }

    public ProfileTrustBusinessException(ProfileTrustErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }
}
