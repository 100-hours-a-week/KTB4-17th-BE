package com.team.dating_backend.profile.service;

import com.team.dating_backend.chat.repository.ChatRoomRepository;
import com.team.dating_backend.matching.repository.LikeRepository;
import com.team.dating_backend.profile.dto.ProfileImageAccessResult;
import com.team.dating_backend.profile.dto.response.UserProfileResponse;
import com.team.dating_backend.profile.entity.Profile;
import com.team.dating_backend.profile.enums.ProfileImageErrorCode;
import com.team.dating_backend.profile.exception.ProfileImageBusinessException;
import com.team.dating_backend.profile.repository.ProfileRepository;
import com.team.dating_backend.recommendation.repository.RecommendationItemRepository;
import com.team.dating_backend.user.enums.UserStatus;
import com.team.dating_backend.user.repository.UserBlockRepository;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserProfileGetService {

    private final ProfileRepository profileRepository;
    private final ProfileImageGetService profileImageGetService;
    private final LikeRepository likeRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final UserBlockRepository userBlockRepository;
    private final RecommendationItemRepository recommendationItemRepository;

    @Transactional(readOnly = true)
    public UserProfileResponse getUserProfile(Long viewerUserId, Long userId) {
        if (viewerUserId == null || userId == null || userId <= 0
            || viewerUserId.equals(userId)
            || userBlockRepository.existsActiveBlockBetween(viewerUserId, userId)
            || (!likeRepository.hasProfileViewAccessBetween(viewerUserId, userId)
                && !chatRoomRepository.existsVisibleBetweenUsers(viewerUserId, userId)
                && !recommendationItemRepository.existsEligibleCandidateInActiveBatch(
                    viewerUserId, userId))) {
            throw profileNotAvailable();
        }

        Profile profile = profileRepository.findByUserIdAndDeletedAtIsNull(userId)
            .filter(candidate -> candidate.getUser().getStatus() == UserStatus.ACTIVE)
            .orElseThrow(UserProfileGetService::profileNotAvailable);

        LocalDate birthDate = profile.getUser().getBirthDate();
        Integer age = birthDate == null ? null : Period.between(birthDate, LocalDate.now()).getYears();
        String region = profile.getActivityRegion() == null
            ? null
            : Stream.of(
                profile.getActivityRegion().getProvinceName(),
                profile.getActivityRegion().getRegionName())
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.joining(" "));
        if (region != null && region.isBlank()) {
            region = null;
        }

        Map<Long, List<ProfileImageAccessResult>> imagesByUserId = profileImageGetService
            .getProfileImagesByUserIds(List.of(userId));
        List<UserProfileResponse.Image> images = imagesByUserId
            .getOrDefault(userId, List.of())
            .stream()
            .map(image -> new UserProfileResponse.Image(
                image.fileId(), image.displayOrder(), image.imageUrl()))
            .toList();

        return new UserProfileResponse(
            userId,
            profile.getNickname(),
            age,
            profile.getJob(),
            region,
            profile.getHeight(),
            profile.getBodyType(),
            profile.getEducationLevel(),
            profile.getReligion(),
            profile.getDrinking(),
            profile.getSmoking(),
            profile.getMbti(),
            images);
    }

    private static ProfileImageBusinessException profileNotAvailable() {
        return new ProfileImageBusinessException(ProfileImageErrorCode.PROFILE_NOT_FOUND);
    }
}
