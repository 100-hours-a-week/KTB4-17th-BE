package com.team.dating_backend.onboarding.exception;

import com.team.dating_backend.common.exception.BusinessException;
import com.team.dating_backend.onboarding.enums.OnboardingErrorCode;

public class UserNotFoundException extends BusinessException {

    public UserNotFoundException(Long userId) {
        super(OnboardingErrorCode.USER_NOT_FOUND, "User not found: " + userId);
    }
}
