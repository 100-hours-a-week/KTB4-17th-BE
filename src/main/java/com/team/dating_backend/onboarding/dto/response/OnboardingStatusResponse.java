package com.team.dating_backend.onboarding.dto.response;

import com.team.dating_backend.onboarding.enums.OnboardingStep;
import com.team.dating_backend.user.enums.UserStatus;

public record OnboardingStatusResponse(
        UserStatus userStatus,
        OnboardingStep onboardingNextStep,
        OnboardingRequirementsResponse requirements) {}
