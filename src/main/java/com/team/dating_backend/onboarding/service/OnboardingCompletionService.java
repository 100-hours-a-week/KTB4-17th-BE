package com.team.dating_backend.onboarding.service;

import com.team.dating_backend.file.dto.FileUploadCompletedEvent;
import com.team.dating_backend.onboarding.exception.UserNotFoundException;
import com.team.dating_backend.profile.entity.Profile;
import com.team.dating_backend.profile.repository.ProfileImageRepository;
import com.team.dating_backend.profile.repository.ProfileRepository;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.UserStatus;
import com.team.dating_backend.user.repository.UserRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OnboardingCompletionService {

    private final UserRepository userRepository;
    private final ProfileRepository profileRepository;
    private final ProfileImageRepository profileImageRepository;
    private final OnboardingRequirementsCalculator requirementsCalculator;
    private final OnboardingTrustRequirement trustRequirement;

    @Transactional
    public void activateIfCompleted(Long userId) {
        User user = userRepository.findByIdForUpdate(userId).orElseThrow(() -> new UserNotFoundException(userId));
        if (user.getStatus() != UserStatus.ONBOARDING && user.getStatus() != UserStatus.ACTIVE) {
            return;
        }
        profileRepository
            .findByUserIdAndDeletedAtIsNull(userId)
            .ifPresent(profile -> {
                boolean completed = isCompleted(user, profile);
                if (user.getStatus() == UserStatus.ONBOARDING && completed) {
                    user.activate(LocalDateTime.now());
                }
            });
    }

    @EventListener
    @Order(1)
    public void synchronizeAfterFileUpload(FileUploadCompletedEvent event) {
        activateIfCompleted(event.ownerUserId());
    }

    private boolean isCompleted(User user, Profile profile) {
        boolean profileImageComplete = profileImageRepository.existsByProfileIdAndDeletedAtIsNullAndFrontalTrue(
            profile.getId());
        boolean profileTrustComplete = profileImageComplete
            && trustRequirement.isSatisfied(user.getId(), profile.getId());
        user.updateFaceVerificationStatus(profileTrustComplete, LocalDateTime.now());
        return requirementsCalculator.calculate(
            profile, user.isPersonaOnboardingComplete(), profileImageComplete, profileTrustComplete).isComplete();
    }
}
