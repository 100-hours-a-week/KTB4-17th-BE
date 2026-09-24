package com.team.dating_backend.onboarding.service;

import com.team.dating_backend.activityregion.entity.ActivityRegion;
import com.team.dating_backend.activityregion.repository.ActivityRegionRepository;
import com.team.dating_backend.common.dto.response.FieldErrorResponse;
import com.team.dating_backend.common.exception.RequestValidationException;
import com.team.dating_backend.onboarding.dto.request.ProfileSaveRequest;
import com.team.dating_backend.onboarding.dto.response.ProfileSaveProfileResponse;
import com.team.dating_backend.onboarding.dto.response.ProfileSaveResponse;
import com.team.dating_backend.onboarding.dto.response.ProfileSaveResult;
import com.team.dating_backend.onboarding.exception.NicknameAlreadyInUseException;
import com.team.dating_backend.onboarding.exception.UserNotFoundException;
import com.team.dating_backend.profile.entity.Profile;
import com.team.dating_backend.profile.repository.ProfileRepository;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProfileSaveService {

    private static final String ACTIVITY_REGION_NOT_FOUND_REASON = "must reference an existing activity region";
    private static final String ACTIVE_NICKNAME_UNIQUE_INDEX = "uk_profiles_active_nickname";

    private final UserRepository userRepository;
    private final ProfileRepository profileRepository;
    private final ActivityRegionRepository activityRegionRepository;

    @Transactional
    public ProfileSaveResult saveProfile(Long userId, ProfileSaveRequest request) {
        User user = userRepository
            .findById(userId)
            .orElseThrow(() -> new UserNotFoundException(userId));
        Optional<Profile> existingProfile = profileRepository.findByUserIdAndDeletedAtIsNull(userId);
        boolean created = existingProfile.isEmpty();
        Profile profile = existingProfile.orElseGet(() -> new Profile(user));

        validateNickname(request.nickname(), userId);
        ActivityRegion activityRegion = findActivityRegion(request.activityRegionId());

        profile.updateProfile(
            activityRegion,
            request.nickname(),
            request.heightAsShort(),
            request.bodyType(),
            request.educationLevel(),
            request.job(),
            request.religion(),
            request.mbti(),
            request.drinking(),
            request.smoking());

        Profile savedProfile = saveAndFlush(profile);
        ProfileSaveResponse response = new ProfileSaveResponse(ProfileSaveProfileResponse.from(savedProfile));
        return new ProfileSaveResult(created, response);
    }

    private Profile saveAndFlush(Profile profile) {
        try {
            return profileRepository.saveAndFlush(profile);
        } catch (DataIntegrityViolationException exception) {
            if (isActiveNicknameUniqueViolation(exception)) {
                throw new NicknameAlreadyInUseException(profile.getNickname());
            }
            throw exception;
        }
    }

    private boolean isActiveNicknameUniqueViolation(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ConstraintViolationException constraintViolation) {
                String constraintName = constraintViolation.getConstraintName();
                if (ACTIVE_NICKNAME_UNIQUE_INDEX.equals(constraintName)
                    || (constraintName != null
                        && constraintName.endsWith("." + ACTIVE_NICKNAME_UNIQUE_INDEX))) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private void validateNickname(String nickname, Long userId) {
        if (nickname != null
            && profileRepository.existsByNicknameAndDeletedAtIsNullAndUserIdNot(
                nickname, userId)) {
            throw new NicknameAlreadyInUseException(nickname);
        }
    }

    private ActivityRegion findActivityRegion(Long activityRegionId) {
        if (activityRegionId == null) {
            return null;
        }

        return activityRegionRepository
            .findById(activityRegionId)
            .orElseThrow(
                () -> new RequestValidationException(
                    List.of(
                        new FieldErrorResponse(
                            "activityRegionId",
                            ACTIVITY_REGION_NOT_FOUND_REASON))));
    }
}
