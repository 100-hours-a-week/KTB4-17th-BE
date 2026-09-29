package com.team.dating_backend.profile.service;

import com.team.dating_backend.file.service.FileAccessUrlCreateService;
import com.team.dating_backend.profile.dto.response.MyProfileResponse;
import com.team.dating_backend.profile.entity.Profile;
import com.team.dating_backend.profile.entity.ProfileImage;
import com.team.dating_backend.profile.repository.ProfileImageRepository;
import com.team.dating_backend.profile.repository.ProfileRepository;
import java.util.Comparator;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MyProfileGetService {

    private static final String INLINE_DISPOSITION = "inline";

    private final ProfileRepository profileRepository;
    private final ProfileImageRepository profileImageRepository;
    private final FileAccessUrlCreateService fileAccessUrlCreateService;

    @Transactional(readOnly = true)
    public Optional<MyProfileResponse> getMyProfile(Long userId) {
        return profileRepository.findByUserIdAndDeletedAtIsNull(userId)
            .map(profile -> new MyProfileResponse(
                profile.getNickname(),
                profile.getUser().getBirthDate(),
                activityRegionId(profile),
                activityRegionName(profile),
                profile.getHeight(),
                profile.getBodyType(),
                profile.getEducationLevel(),
                profile.getJob(),
                profile.getReligion(),
                profile.getMbti(),
                profile.getDrinking(),
                profile.getSmoking(),
                profileImageUrl(profile)));
    }

    private Long activityRegionId(Profile profile) {
        return profile.getActivityRegion() == null ? null : profile.getActivityRegion().getId();
    }

    private String activityRegionName(Profile profile) {
        if (profile.getActivityRegion() == null) {
            return null;
        }
        return profile.getActivityRegion().getProvinceName() + " "
            + profile.getActivityRegion().getRegionName();
    }

    private String profileImageUrl(Profile profile) {
        return profileImageRepository.findByProfileIdAndDeletedAtIsNull(profile.getId()).stream()
            .sorted(
                Comparator.comparing(ProfileImage::isFrontal)
                    .reversed()
                    .thenComparingInt(ProfileImage::getDisplayOrder))
            .findFirst()
            .map(profileImage -> fileAccessUrlCreateService.createPresignedAccessUrl(
                profileImage.getImage(), INLINE_DISPOSITION).accessUrl())
            .orElse(null);
    }
}
