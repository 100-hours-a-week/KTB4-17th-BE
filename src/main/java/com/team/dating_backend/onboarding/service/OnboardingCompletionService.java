package com.team.dating_backend.onboarding.service;

import com.team.dating_backend.onboarding.exception.UserNotFoundException;
import com.team.dating_backend.profile.entity.Profile;
import com.team.dating_backend.profile.repository.ProfileImageRepository;
import com.team.dating_backend.profile.repository.ProfileRepository;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.UserStatus;
import com.team.dating_backend.user.repository.UserRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OnboardingCompletionService {

    private final UserRepository userRepository;
    private final ProfileRepository profileRepository;
    private final ProfileImageRepository profileImageRepository;
    private final OnboardingRequirementsCalculator requirementsCalculator;

    @Transactional
    public void activateIfCompleted(Long userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));
        if (user.getStatus() != UserStatus.ONBOARDING) {
            return;
        }

        profileRepository
            .findByUserIdAndDeletedAtIsNull(userId)
            .filter(profile -> isCompleted(user, profile))
            .ifPresent(profile -> user.activate(LocalDateTime.now()));
    }

    private boolean isCompleted(User user, Profile profile) {
        boolean profileImageComplete = profileImageRepository.existsByProfileIdAndDeletedAtIsNullAndFrontalTrue(
            profile.getId());
        return requirementsCalculator.calculate(
            profile, user.isPersonaOnboardingComplete(), profileImageComplete).isComplete();
    }
}
