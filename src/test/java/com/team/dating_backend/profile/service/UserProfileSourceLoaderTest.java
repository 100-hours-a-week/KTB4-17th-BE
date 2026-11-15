package com.team.dating_backend.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
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
import com.team.dating_backend.user.enums.UserStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UserProfileSourceLoaderTest {

    private static final Instant NOW = Instant.parse("2026-11-04T03:00:00Z");
    private ProfileRepository profiles;
    private ProfileImageGetService images;
    private UserProfileSourceLoader loader;
    private User user;
    private Profile profile;

    @BeforeEach
    void setUp() {
        profiles = mock(ProfileRepository.class);
        images = mock(ProfileImageGetService.class);
        loader = new UserProfileSourceLoader(profiles, images, Clock.fixed(NOW, ZoneOffset.UTC));
        user = mock(User.class);
        given(user.getStatus()).willReturn(UserStatus.ACTIVE);
        given(user.getBirthDate()).willReturn(LocalDate.now().minusYears(29));
        profile = mock(Profile.class);
        given(profile.getUser()).willReturn(user);
        given(profile.getNickname()).willReturn("하리");
        given(profiles.findByUserIdAndDeletedAtIsNull(21L)).willReturn(Optional.of(profile));
        given(images.getProfileImagesByUserIds(List.of(21L))).willReturn(Map.of());
    }

    @Test
    void 기존_응답_필드와_가장_이른_이미지_만료시각을_함께_반환한다() {
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
        given(images.getProfileImagesByUserIds(List.of(21L))).willReturn(Map.of(21L, List.of(
            new ProfileImageAccessResult(701L, (short) 1, "https://example.com/701", NOW.plusSeconds(600)),
            new ProfileImageAccessResult(702L, (short) 2, "https://example.com/702", NOW.plusSeconds(120)))));

        var loaded = loader.load(21L);

        assertThat(loaded.profile()).isEqualTo(new UserProfileResponse(21L, "하리", 29, JobCategory.IT_DATA,
            "서울특별시 강남구", (short) 175, BodyType.AVERAGE, EducationLevel.BACHELOR, Religion.NONE,
            Drinking.SOCIAL, Smoking.NON_SMOKER, Mbti.INTJ, List.of(
                new UserProfileResponse.Image(701L, (short) 1, "https://example.com/701"),
                new UserProfileResponse.Image(702L, (short) 2, "https://example.com/702"))));
        assertThat(loaded.loadedAt()).isEqualTo(NOW);
        assertThat(loaded.earliestImageExpiresAt()).isEqualTo(NOW.plusSeconds(120));
    }

    @Test
    void 이미지가_없고_생일과_지역이_없어도_기존_null_응답을_유지한다() {
        given(user.getBirthDate()).willReturn(null);

        var loaded = loader.load(21L);

        assertThat(loaded.profile().age()).isNull();
        assertThat(loaded.profile().region()).isNull();
        assertThat(loaded.profile().images()).isEmpty();
        assertThat(loaded.earliestImageExpiresAt()).isNull();
    }

    @Test
    void 일부_이미지의_만료정보가_없으면_전체_만료정보를_사용하지_않는다() {
        given(images.getProfileImagesByUserIds(List.of(21L))).willReturn(Map.of(21L, List.of(
            new ProfileImageAccessResult(701L, (short) 1, "https://example.com/701", NOW.plusSeconds(600)),
            new ProfileImageAccessResult(702L, (short) 2, "https://example.com/702", null))));

        var loaded = loader.load(21L);

        assertThat(loaded.profile().images()).hasSize(2);
        assertThat(loaded.earliestImageExpiresAt()).isNull();
    }

    @Test
    void 본문_조회_시점에_비활성이면_기존_조회불가_오류를_반환한다() {
        given(user.getStatus()).willReturn(UserStatus.SUSPENDED);
        assertUnavailable();
    }

    @Test
    void 본문이_사라졌으면_기존_조회불가_오류를_반환한다() {
        given(profiles.findByUserIdAndDeletedAtIsNull(21L)).willReturn(Optional.empty());
        assertUnavailable();
    }

    private void assertUnavailable() {
        assertThatThrownBy(() -> loader.load(21L)).isInstanceOfSatisfying(ProfileImageBusinessException.class,
            failure -> assertThat(failure.getErrorCode()).isEqualTo(ProfileImageErrorCode.PROFILE_NOT_FOUND));
        verifyNoInteractions(images);
    }
}
