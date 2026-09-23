package com.team.dating_backend.onboarding.exception;

import com.team.dating_backend.common.exception.BusinessException;
import com.team.dating_backend.onboarding.enums.OnboardingErrorCode;

public class NicknameAlreadyInUseException extends BusinessException {

    public NicknameAlreadyInUseException(String nickname) {
        super(
                OnboardingErrorCode.NICKNAME_ALREADY_IN_USE,
                "Nickname is already in use: " + nickname);
    }
}
