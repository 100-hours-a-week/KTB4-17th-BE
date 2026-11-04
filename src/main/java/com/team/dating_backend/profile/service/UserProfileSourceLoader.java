package com.team.dating_backend.profile.service;

import com.team.dating_backend.profile.dto.ProfileImageAccessResult;
import com.team.dating_backend.profile.dto.UserProfileLoadResult;
import com.team.dating_backend.profile.dto.response.UserProfileResponse;
import com.team.dating_backend.profile.entity.Profile;
import com.team.dating_backend.profile.enums.ProfileImageErrorCode;
import com.team.dating_backend.profile.exception.ProfileImageBusinessException;
import com.team.dating_backend.profile.repository.ProfileRepository;
import com.team.dating_backend.user.enums.UserStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserProfileSourceLoader {

    private final ProfileRepository profileRepository;
    private final ProfileImageGetService profileImageGetService;
    private final Clock clock;

    @Autowired
    public UserProfileSourceLoader(ProfileRepository profileRepository, ProfileImageGetService profileImageGetService) {
        this(profileRepository, profileImageGetService, Clock.systemUTC());
    }

    UserProfileSourceLoader(ProfileRepository profileRepository, ProfileImageGetService profileImageGetService,
        Clock clock) {
        this.profileRepository = profileRepository;
        this.profileImageGetService = profileImageGetService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public UserProfileLoadResult load(Long userId) {
        Instant loadedAt = clock.instant();
        Profile profile = profileRepository.findByUserIdAndDeletedAtIsNull(userId)
            .filter(candidate -> candidate.getUser().getStatus() == UserStatus.ACTIVE)
            .orElseThrow(() -> new ProfileImageBusinessException(ProfileImageErrorCode.PROFILE_NOT_FOUND));
        LocalDate birthDate = profile.getUser().getBirthDate();
        Integer age = birthDate == null ? null : Period.between(birthDate, LocalDate.now()).getYears();
        String region = profile.getActivityRegion() == null
            ? null
            : Stream.of(
                profile.getActivityRegion().getProvinceName(), profile.getActivityRegion().getRegionName())
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.joining(" "));
        if (region != null && region.isBlank()) {
            region = null;
        }
        List<ProfileImageAccessResult> imageAccess = profileImageGetService
            .getProfileImagesByUserIds(List.of(userId)).getOrDefault(userId, List.of());
        List<UserProfileResponse.Image> images = imageAccess.stream()
            .map(image -> new UserProfileResponse.Image(image.fileId(), image.displayOrder(), image.imageUrl()))
            .toList();
        Instant earliestImageExpiresAt = imageAccess.stream().anyMatch(image -> image.expiresAt() == null)
            ? null
            : imageAccess.stream().map(ProfileImageAccessResult::expiresAt).min(Comparator.naturalOrder())
                .orElse(null);
        UserProfileResponse response = new UserProfileResponse(userId, profile.getNickname(), age, profile.getJob(),
            region, profile.getHeight(), profile.getBodyType(), profile.getEducationLevel(), profile.getReligion(),
            profile.getDrinking(), profile.getSmoking(), profile.getMbti(), images);
        return new UserProfileLoadResult(response, loadedAt, earliestImageExpiresAt);
    }
}
