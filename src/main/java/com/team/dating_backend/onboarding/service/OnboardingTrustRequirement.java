package com.team.dating_backend.onboarding.service;

public interface OnboardingTrustRequirement {

    boolean isSatisfied(Long userId, Long profileId);
}
