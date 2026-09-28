package com.team.dating_backend.onboarding.exception;

import com.team.dating_backend.common.exception.BusinessException;
import com.team.dating_backend.onboarding.enums.OnboardingErrorCode;
import com.team.dating_backend.user.enums.UserStatus;

public class OnboardingAccessNotAllowedException extends BusinessException {

    public OnboardingAccessNotAllowedException(Long userId, UserStatus userStatus) {
        super(
            OnboardingErrorCode.ONBOARDING_ACCESS_NOT_ALLOWED,
            "Onboarding access is not allowed for user "
                + userId
                + " with status "
                + userStatus);
    }
}
