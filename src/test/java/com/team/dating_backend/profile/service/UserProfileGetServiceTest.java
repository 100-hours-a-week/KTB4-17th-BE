package com.team.dating_backend.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.team.dating_backend.activityregion.entity.ActivityRegion;
import com.team.dating_backend.profile.dto.ProfileImageAccessResult;
import com.team.dating_backend.profile.dto.response.UserProfileResponse;
import com.team.dating_backend.profile.entity.Profile;
import com.team.dating_backend.profile.enums.BodyType;
import com.team.dating_backend.profile.enums.Drinking;
import com.team.dating_backend.profile.enums.EducationLevel;
import com.team.dating_backend.profile.enums.JobCategory;
import com.team.dating_backend.profile.enums.Mbti;
import com.team.dating_backend.profile.enums.ProfileImageErrorCode;
import com.team.dating_backend.profile.enums.Religion;
import com.team.dating_backend.profile.enums.Smoking;
import com.team.dating_backend.profile.exception.ProfileImageBusinessException;
import com.team.dating_backend.profile.repository.ProfileRepository;
import com.team.dating_backend.user.entity.User;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class UserProfileGetServiceTest {

    private ProfileRepository profileRepository;
    private ProfileImageGetService profileImageGetService;
    private ProfileAvailabilityValidator profileAvailabilityValidator;
    private UserProfileGetService service;

    @BeforeEach
    void setUp() {
        profileRepository = mock(ProfileRepository.class);
        profileImageGetService = mock(ProfileImageGetService.class);
        profileAvailabilityValidator = mock(ProfileAvailabilityValidator.class);
        service = new UserProfileGetService(profileRepository, profileImageGetService,
            profileAvailabilityValidator);
    }

    @Test
    void 관계_조회_없이_프로필을_기존_응답_형식으로_반환한다() {
        Profile profile = givenProfile(21L);
        ActivityRegion region = mock(ActivityRegion.class);
        given(region.getProvinceName()).willReturn("서울특별시");
        given(region.getRegionName()).willReturn("강남구");
        given(profile.getActivityRegion()).willReturn(region);
        given(profile.getJob()).willReturn(JobCategory.IT_DATA);
        given(profile.getHeight()).willReturn((short) 175);
        given(profile.getBodyType()).willReturn(BodyType.AVERAGE);
        given(profile.getEducationLevel()).willReturn(EducationLevel.BACHELOR);
        given(profile.getReligion()).willReturn(Religion.NONE);
        given(profile.getDrinking()).willReturn(Drinking.SOCIAL);
        given(profile.getSmoking()).willReturn(Smoking.NON_SMOKER);
        given(profile.getMbti()).willReturn(Mbti.INTJ);

        UserProfileResponse response = service.getUserProfile(5L, 21L);

        assertThat(response).isEqualTo(new UserProfileResponse(
            21L, "하리", 29, JobCategory.IT_DATA, "서울특별시 강남구", (short) 175,
            BodyType.AVERAGE, EducationLevel.BACHELOR, Religion.NONE, Drinking.SOCIAL,
            Smoking.NON_SMOKER, Mbti.INTJ,
            List.of(new UserProfileResponse.Image(701L, (short) 1, "https://example.com/701"))));
        verify(profileAvailabilityValidator).validate(21L);
    }

    @Test
    void 본인도_동일한_상태_검증을_거쳐_프로필을_조회한다() {
        givenProfile(5L);

        UserProfileResponse response = service.getUserProfile(5L, 5L);

        assertThat(response.userId()).isEqualTo(5L);
        assertThat(response.nickname()).isEqualTo("하리");
        assertThat(response.images()).hasSize(1);
        verify(profileAvailabilityValidator).validate(5L);
    }

    @ParameterizedTest
    @ValueSource(longs = {5L, 21L})
    void 본인이나_상대가_조회_불가이면_본문과_이미지를_조회하지_않는다(Long userId) {
        willThrow(new ProfileImageBusinessException(ProfileImageErrorCode.PROFILE_NOT_FOUND))
            .given(profileAvailabilityValidator).validate(userId);

        assertUnavailable(5L, userId);

        verifyNoInteractions(profileRepository, profileImageGetService);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0L, -1L})
    void 잘못된_대상_ID는_상태_검증과_본문_조회_전에_거절한다(Long userId) {
        assertUnavailable(5L, userId);

        verifyNoInteractions(profileAvailabilityValidator, profileRepository, profileImageGetService);
    }

    @Test
    void 조회자_ID가_없으면_거절한다() {
        assertUnavailable(null, 21L);

        verifyNoInteractions(profileAvailabilityValidator, profileRepository, profileImageGetService);
    }

    @Test
    void 상태_검증_이후에도_본문을_찾지_못하면_기존_조회_불가_오류를_반환한다() {
        assertUnavailable(5L, 21L);

        verify(profileAvailabilityValidator).validate(21L);
        verifyNoInteractions(profileImageGetService);
    }

    private Profile givenProfile(Long userId) {
        User user = mock(User.class);
        given(user.getBirthDate()).willReturn(LocalDate.now().minusYears(29));
        Profile profile = mock(Profile.class);
        given(profile.getUser()).willReturn(user);
        given(profile.getNickname()).willReturn("하리");
        given(profileRepository.findByUserIdAndDeletedAtIsNull(userId))
            .willReturn(Optional.of(profile));
        given(profileImageGetService.getProfileImagesByUserIds(List.of(userId)))
            .willReturn(Map.of(userId, List.of(new ProfileImageAccessResult(
                701L, (short) 1, "https://example.com/701",
                Instant.parse("2026-09-27T12:00:00Z")))));
        return profile;
    }

    private void assertUnavailable(Long viewerUserId, Long userId) {
        assertThatThrownBy(() -> service.getUserProfile(viewerUserId, userId))
            .isInstanceOf(ProfileImageBusinessException.class)
            .satisfies(exception -> assertThat(
                ((ProfileImageBusinessException) exception).getErrorCode())
                .isEqualTo(ProfileImageErrorCode.PROFILE_NOT_FOUND));
    }
}
