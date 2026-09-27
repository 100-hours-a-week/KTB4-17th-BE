package com.team.dating_backend.onboarding.service;

import com.team.dating_backend.onboarding.dto.response.OnboardingRequirementsResponse;
import com.team.dating_backend.profile.entity.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class OnboardingRequirementsCalculator {

    public OnboardingRequirementsResponse calculate(
        Profile profile, boolean personaComplete, boolean profileImageComplete) {
        boolean nicknameComplete = StringUtils.hasText(profile.getNickname());
        boolean regionComplete = profile.getActivityRegion() != null;
        boolean basicInfoComplete = profile.getHeight() != null
            && profile.getBodyType() != null
            && profile.getEducationLevel() != null
            && StringUtils.hasText(profile.getJob());
        boolean lifestyleComplete = profile.getReligion() != null
            && profile.getDrinking() != null
            && profile.getSmoking() != null;
        boolean mbtiComplete = profile.getMbti() != null;

        return new OnboardingRequirementsResponse(
            nicknameComplete,
            regionComplete,
            basicInfoComplete,
            lifestyleComplete,
            mbtiComplete,
            personaComplete,
            profileImageComplete);
    }
}
