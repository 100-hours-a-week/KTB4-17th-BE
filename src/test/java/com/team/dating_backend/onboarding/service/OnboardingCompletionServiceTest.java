package com.team.dating_backend.onboarding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.team.dating_backend.activityregion.entity.ActivityRegion;
import com.team.dating_backend.profile.entity.Profile;
import com.team.dating_backend.profile.enums.BodyType;
import com.team.dating_backend.profile.enums.Drinking;
import com.team.dating_backend.profile.enums.EducationLevel;
import com.team.dating_backend.profile.enums.Mbti;
import com.team.dating_backend.profile.enums.Religion;
import com.team.dating_backend.profile.enums.Smoking;
import com.team.dating_backend.profile.repository.ProfileImageRepository;
import com.team.dating_backend.profile.repository.ProfileRepository;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.Gender;
import com.team.dating_backend.user.enums.PersonaOnboardingStatus;
import com.team.dating_backend.user.enums.UserStatus;
import com.team.dating_backend.user.repository.UserRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

class OnboardingCompletionServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long PROFILE_ID = 10L;

    private UserRepository userRepository;
    private ProfileRepository profileRepository;
    private ProfileImageRepository profileImageRepository;
    private OnboardingCompletionService onboardingCompletionService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        profileRepository = mock(ProfileRepository.class);
        profileImageRepository = mock(ProfileImageRepository.class);
        onboardingCompletionService = new OnboardingCompletionService(
            userRepository,
            profileRepository,
            profileImageRepository,
            new OnboardingRequirementsCalculator());
    }

    @Test
    void 모든_프로필_조건과_정면_사진이_있으면_회원이_ACTIVE가_된다() {
        User user = onboardingUser();
        Profile profile = completeProfile(user);
        givenUser(user);
        given(profileRepository.findByUserIdAndDeletedAtIsNull(USER_ID))
            .willReturn(Optional.of(profile));
        given(profileImageRepository.existsByProfileIdAndDeletedAtIsNullAndFrontalTrue(PROFILE_ID))
            .willReturn(true);

        onboardingCompletionService.activateIfCompleted(USER_ID);

        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getUpdatedAt()).isAfter(user.getCreatedAt());
    }

    @Test
    void 페르소나_온보딩이_PENDING이면_다른_조건을_충족해도_ONBOARDING을_유지한다() {
        User user = pendingPersonaUser();
        assertThat(user.getPersonaOnboardingStatus()).isEqualTo(PersonaOnboardingStatus.PENDING);
        Profile profile = completeProfile(user);
        givenUser(user);
        given(profileRepository.findByUserIdAndDeletedAtIsNull(USER_ID))
            .willReturn(Optional.of(profile));
        given(profileImageRepository.existsByProfileIdAndDeletedAtIsNullAndFrontalTrue(PROFILE_ID))
            .willReturn(true);

        onboardingCompletionService.activateIfCompleted(USER_ID);

        assertThat(user.getStatus()).isEqualTo(UserStatus.ONBOARDING);
    }

    @Test
    void 페르소나_온보딩이_IN_PROGRESS이면_다른_조건을_충족해도_ONBOARDING을_유지한다() {
        User user = pendingPersonaUser();
        user.startPersonaOnboarding(LocalDateTime.now().plusSeconds(1));
        assertThat(user.getPersonaOnboardingStatus())
            .isEqualTo(PersonaOnboardingStatus.IN_PROGRESS);
        Profile profile = completeProfile(user);
        givenUser(user);
        given(profileRepository.findByUserIdAndDeletedAtIsNull(USER_ID))
            .willReturn(Optional.of(profile));
        given(profileImageRepository.existsByProfileIdAndDeletedAtIsNullAndFrontalTrue(PROFILE_ID))
            .willReturn(true);

        onboardingCompletionService.activateIfCompleted(USER_ID);

        assertThat(user.getStatus()).isEqualTo(UserStatus.ONBOARDING);
    }

    @Test
    void 페르소나_온보딩이_BYPASSED이면_다른_조건_충족_시_ACTIVE가_된다() {
        User user = pendingPersonaUser();
        ReflectionTestUtils.setField(
            user, "personaOnboardingStatus", PersonaOnboardingStatus.BYPASSED);
        Profile profile = completeProfile(user);
        givenUser(user);
        given(profileRepository.findByUserIdAndDeletedAtIsNull(USER_ID))
            .willReturn(Optional.of(profile));
        given(profileImageRepository.existsByProfileIdAndDeletedAtIsNullAndFrontalTrue(PROFILE_ID))
            .willReturn(true);

        onboardingCompletionService.activateIfCompleted(USER_ID);

        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void 정면_사진이_없으면_회원은_ONBOARDING을_유지한다() {
        User user = onboardingUser();
        Profile profile = completeProfile(user);
        givenUser(user);
        given(profileRepository.findByUserIdAndDeletedAtIsNull(USER_ID))
            .willReturn(Optional.of(profile));
        given(profileImageRepository.existsByProfileIdAndDeletedAtIsNullAndFrontalTrue(PROFILE_ID))
            .willReturn(false);

        onboardingCompletionService.activateIfCompleted(USER_ID);

        assertThat(user.getStatus()).isEqualTo(UserStatus.ONBOARDING);
    }

    @Test
    void 프로필_조건이_부족하면_정면_사진이_있어도_ONBOARDING을_유지한다() {
        User user = onboardingUser();
        Profile profile = completeProfile(user);
        profile.updateProfile(
            profile.getActivityRegion(),
            profile.getNickname(),
            profile.getHeight(),
            profile.getBodyType(),
            profile.getEducationLevel(),
            profile.getJob(),
            profile.getReligion(),
            null,
            profile.getDrinking(),
            profile.getSmoking());
        givenUser(user);
        given(profileRepository.findByUserIdAndDeletedAtIsNull(USER_ID))
            .willReturn(Optional.of(profile));
        given(profileImageRepository.existsByProfileIdAndDeletedAtIsNullAndFrontalTrue(PROFILE_ID))
            .willReturn(true);

        onboardingCompletionService.activateIfCompleted(USER_ID);

        assertThat(user.getStatus()).isEqualTo(UserStatus.ONBOARDING);
    }

    @Test
    void ACTIVE_회원은_상태를_유지하고_완료_조건을_다시_조회하지_않는다() {
        User user = onboardingUser();
        user.activate(LocalDateTime.now());
        givenUser(user);

        onboardingCompletionService.activateIfCompleted(USER_ID);

        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        verifyNoInteractions(profileRepository, profileImageRepository);
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"SUSPENDED", "WITHDRAWN"})
    void 정지_또는_탈퇴_회원은_활성화하지_않는다(UserStatus status) {
        User user = onboardingUser();
        ReflectionTestUtils.setField(user, "status", status);
        givenUser(user);

        onboardingCompletionService.activateIfCompleted(USER_ID);

        assertThat(user.getStatus()).isEqualTo(status);
        verifyNoInteractions(profileRepository, profileImageRepository);
    }

    private User onboardingUser() {
        User user = pendingPersonaUser();
        user.confirmPersonaOnboarding(LocalDateTime.now().minusNanos(1));
        return user;
    }

    private User pendingPersonaUser() {
        LocalDateTime createdAt = LocalDateTime.now().minusSeconds(1);
        User user = User.create("하리", LocalDate.of(2000, 1, 1), Gender.FEMALE, createdAt);
        ReflectionTestUtils.setField(user, "id", USER_ID);
        return user;
    }

    private void givenUser(User user) {
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
    }

    private Profile completeProfile(User user) {
        Profile profile = new Profile(user);
        ReflectionTestUtils.setField(profile, "id", PROFILE_ID);
        profile.updateProfile(
            mock(ActivityRegion.class),
            "하리",
            (short) 175,
            BodyType.AVERAGE,
            EducationLevel.BACHELOR,
            "개발자",
            Religion.NONE,
            Mbti.INTJ,
            Drinking.SOCIAL,
            Smoking.NON_SMOKER);
        return profile;
    }
}
