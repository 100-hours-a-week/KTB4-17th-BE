package com.team.dating_backend.profile.service;

import com.team.dating_backend.profile.enums.ProfileImageErrorCode;
import com.team.dating_backend.profile.exception.ProfileImageBusinessException;
import com.team.dating_backend.profile.repository.ProfileRepository;
import com.team.dating_backend.user.enums.UserStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class ProfileAvailabilityValidator {

    private final ProfileRepository profileRepository;

    @Transactional(readOnly = true)
    public void validate(Long userId) {
        if (!profileRepository.existsByUserIdAndUserStatusAndDeletedAtIsNull(
            userId, UserStatus.ACTIVE)) {
            throw new ProfileImageBusinessException(ProfileImageErrorCode.PROFILE_NOT_FOUND);
        }
    }
}
