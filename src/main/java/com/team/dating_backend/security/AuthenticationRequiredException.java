package com.team.dating_backend.security;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.common.exception.BusinessException;

public class AuthenticationRequiredException extends BusinessException {

    public AuthenticationRequiredException() {
        super(CommonErrorCode.AUTH_REQUIRED);
    }
}
