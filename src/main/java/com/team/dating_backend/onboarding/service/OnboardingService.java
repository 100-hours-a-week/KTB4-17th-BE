package com.team.dating_backend.onboarding.service;

import com.team.dating_backend.onboarding.dto.response.OnboardingProfileResponse;
import com.team.dating_backend.onboarding.dto.response.OnboardingRequirementsResponse;
import com.team.dating_backend.onboarding.dto.response.OnboardingStatusResponse;
import com.team.dating_backend.onboarding.enums.OnboardingStep;
import com.team.dating_backend.onboarding.exception.OnboardingAccessNotAllowedException;
import com.team.dating_backend.onboarding.exception.UserNotFoundException;
import com.team.dating_backend.profile.entity.Profile;
import com.team.dating_backend.profile.repository.ProfileRepository;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.UserStatus;
import com.team.dating_backend.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class OnboardingService {

    private final UserRepository userRepository;
    private final ProfileRepository profileRepository;

    @Transactional(readOnly = true)
    public OnboardingStatusResponse getOnboardingStatus(Long userId) {
        User user = findUser(userId);
        validateOnboardingAccess(userId, user);

        if (user.getStatus() == UserStatus.ACTIVE) {
            return new OnboardingStatusResponse(
                    user.getStatus(),
                    OnboardingStep.COMPLETE,
                    OnboardingRequirementsResponse.complete());
        }

        OnboardingRequirementsResponse requirements =
                profileRepository
                        .findByUserIdAndDeletedAtIsNull(userId)
                        .map(this::calculateRequirements)
                        .orElseGet(OnboardingRequirementsResponse::incomplete);

        return new OnboardingStatusResponse(
                user.getStatus(), determineNextStep(requirements), requirements);
    }

    @Transactional(readOnly = true)
    public OnboardingProfileResponse getOnboardingProfile(Long userId) {
        User user = findUser(userId);
        validateOnboardingAccess(userId, user);
        return new OnboardingProfileResponse(user.getId(), user.getBirthDate(), user.getGender());
    }

    private User findUser(Long userId) {
        return userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));
    }

    private void validateOnboardingAccess(Long userId, User user) {
        if (user.getStatus() != UserStatus.ONBOARDING && user.getStatus() != UserStatus.ACTIVE) {
            throw new OnboardingAccessNotAllowedException(userId, user.getStatus());
        }
    }

    private OnboardingRequirementsResponse calculateRequirements(Profile profile) {
        boolean nicknameComplete = StringUtils.hasText(profile.getNickname());
        boolean regionComplete = profile.getActivityRegion() != null;
        boolean basicInfoComplete =
                profile.getHeight() != null
                        && profile.getBodyType() != null
                        && profile.getEducationLevel() != null
                        && StringUtils.hasText(profile.getJob());
        boolean lifestyleComplete =
                profile.getReligion() != null
                        && profile.getDrinking() != null
                        && profile.getSmoking() != null;
        boolean mbtiComplete = profile.getMbti() != null;

        return new OnboardingRequirementsResponse(
                nicknameComplete,
                regionComplete,
                basicInfoComplete,
                lifestyleComplete,
                mbtiComplete);
    }

    private OnboardingStep determineNextStep(OnboardingRequirementsResponse requirements) {
        if (!requirements.nicknameComplete()) {
            return OnboardingStep.NICKNAME;
        }
        if (!requirements.regionComplete()) {
            return OnboardingStep.REGION;
        }
        if (!requirements.basicInfoComplete()) {
            return OnboardingStep.PROFILE;
        }
        if (!requirements.lifestyleComplete()) {
            return OnboardingStep.LIFESTYLE;
        }
        if (!requirements.mbtiComplete()) {
            return OnboardingStep.MBTI;
        }
        return OnboardingStep.COMPLETE;
    }
}
