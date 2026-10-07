package com.team.dating_backend.onboarding.exception;

import com.team.dating_backend.common.exception.BusinessException;
import com.team.dating_backend.onboarding.enums.OnboardingErrorCode;
import com.team.dating_backend.user.enums.PersonaOnboardingStatus;

public class ProfileUpdateNotAllowedException extends BusinessException {

    public ProfileUpdateNotAllowedException(
        Long userId,
        PersonaOnboardingStatus personaOnboardingStatus) {
        super(
            OnboardingErrorCode.PROFILE_UPDATE_NOT_ALLOWED,
            "Profile update is not allowed for user "
                + userId
                + " with persona onboarding status "
                + personaOnboardingStatus);
    }
}
