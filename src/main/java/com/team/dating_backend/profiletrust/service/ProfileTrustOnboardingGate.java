package com.team.dating_backend.profiletrust.service;

import com.team.dating_backend.file.dto.FileUploadCompletedEvent;
import com.team.dating_backend.onboarding.service.OnboardingTrustRequirement;
import com.team.dating_backend.profiletrust.repository.FaceVerificationSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProfileTrustOnboardingGate implements OnboardingTrustRequirement {

    private final FaceVerificationSessionRepository sessions;

    @EventListener
    public void bindVerifiedPhoto(FileUploadCompletedEvent event) {
        if (event.validatedVersionToken() != null) {
            sessions.bindVerifiedFile(event.ownerUserId(), event.uploadIntentId(),
                event.validatedVersionToken(), event.fileId());
        }
    }

    @Override
    public boolean isSatisfied(Long userId, Long profileId) {
        return sessions.existsVerifiedProfilePhoto(userId, profileId);
    }
}
