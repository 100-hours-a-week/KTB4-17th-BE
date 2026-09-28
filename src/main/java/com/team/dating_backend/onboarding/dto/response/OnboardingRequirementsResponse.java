package com.team.dating_backend.onboarding.dto.response;

public record OnboardingRequirementsResponse(
    boolean nicknameComplete,
    boolean regionComplete,
    boolean basicInfoComplete,
    boolean lifestyleComplete,
    boolean mbtiComplete,
    boolean personaComplete,
    boolean profileImageComplete) {

    public static OnboardingRequirementsResponse complete() {
        return new OnboardingRequirementsResponse(true, true, true, true, true, true, true);
    }

    public static OnboardingRequirementsResponse incomplete() {
        return new OnboardingRequirementsResponse(false, false, false, false, false, false, false);
    }

    public boolean isComplete() {
        return nicknameComplete
            && regionComplete
            && basicInfoComplete
            && lifestyleComplete
            && mbtiComplete
            && personaComplete
            && profileImageComplete;
    }
}
