package com.team.dating_backend.profile.service;

import com.team.dating_backend.file.service.FileAccessUrlCreateService;
import com.team.dating_backend.profile.dto.response.MyProfileResponse;
import com.team.dating_backend.profile.entity.Profile;
import com.team.dating_backend.profile.entity.ProfileImage;
import com.team.dating_backend.profile.repository.ProfileImageRepository;
import com.team.dating_backend.profile.repository.ProfileRepository;
import java.util.Comparator;
import java.util.List;
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
            .map(profile -> {
                List<MyProfileResponse.Image> images = profileImages(profile);
                String profileImageUrl = images.isEmpty() ? null : images.get(0).imageUrl();
                return new MyProfileResponse(
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
                    profileImageUrl,
                    images);
            });
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

    private List<MyProfileResponse.Image> profileImages(Profile profile) {
        return profileImageRepository.findByProfileIdAndDeletedAtIsNull(profile.getId()).stream()
            .sorted(Comparator.comparingInt(ProfileImage::getDisplayOrder))
            .map(profileImage -> new MyProfileResponse.Image(
                profileImage.getImage().getId(),
                profileImage.getDisplayOrder(),
                profileImage.isFrontal(),
                fileAccessUrlCreateService.createPresignedAccessUrl(
                    profileImage.getImage(), INLINE_DISPOSITION).accessUrl()))
            .toList();
    }
}
