package com.team.dating_backend.onboarding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.team.dating_backend.activityregion.entity.ActivityRegion;
import com.team.dating_backend.onboarding.dto.response.OnboardingProfileResponse;
import com.team.dating_backend.onboarding.dto.response.OnboardingRequirementsResponse;
import com.team.dating_backend.onboarding.dto.response.OnboardingStatusResponse;
import com.team.dating_backend.onboarding.enums.OnboardingStep;
import com.team.dating_backend.onboarding.exception.OnboardingAccessNotAllowedException;
import com.team.dating_backend.onboarding.exception.UserNotFoundException;
import com.team.dating_backend.profile.entity.Profile;
import com.team.dating_backend.profile.enums.BodyType;
import com.team.dating_backend.profile.enums.Drinking;
import com.team.dating_backend.profile.enums.EducationLevel;
import com.team.dating_backend.profile.enums.Mbti;
import com.team.dating_backend.profile.enums.Religion;
import com.team.dating_backend.profile.enums.Smoking;
import com.team.dating_backend.profile.repository.ProfileRepository;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.Gender;
import com.team.dating_backend.user.enums.UserStatus;
import com.team.dating_backend.user.repository.UserRepository;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class OnboardingServiceTest {

    private static final Long USER_ID = 1L;

    private UserRepository userRepository;
    private ProfileRepository profileRepository;
    private OnboardingService onboardingService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        profileRepository = mock(ProfileRepository.class);
        onboardingService = new OnboardingService(userRepository, profileRepository);
    }

    @Test
    void 프로필이_존재하지_않으면_다음_단계는_NICKNAME이다() {
        givenOnboardingUser();
        given(profileRepository.findByUserIdAndDeletedAtIsNull(USER_ID))
            .willReturn(Optional.empty());

        OnboardingStatusResponse response = onboardingService.getOnboardingStatus(USER_ID);

        assertThat(response.userStatus()).isEqualTo(UserStatus.ONBOARDING);
        assertThat(response.onboardingNextStep()).isEqualTo(OnboardingStep.NICKNAME);
        assertThat(response.requirements()).isEqualTo(OnboardingRequirementsResponse.incomplete());
    }

    @Test
    void 닉네임이_공백이면_다음_단계는_NICKNAME이다() {
        givenOnboardingUser();
        Profile profile = completeProfile();
        given(profile.getNickname()).willReturn("   ");
        givenProfile(profile);

        OnboardingStatusResponse response = onboardingService.getOnboardingStatus(USER_ID);

        assertThat(response.onboardingNextStep()).isEqualTo(OnboardingStep.NICKNAME);
        assertThat(response.requirements().nicknameComplete()).isFalse();
        assertThat(response.requirements().regionComplete()).isTrue();
        assertThat(response.requirements().basicInfoComplete()).isTrue();
        assertThat(response.requirements().lifestyleComplete()).isTrue();
        assertThat(response.requirements().mbtiComplete()).isTrue();
    }

    @Test
    void 활동_지역이_없으면_다음_단계는_REGION이다() {
        givenOnboardingUser();
        Profile profile = completeProfile();
        given(profile.getActivityRegion()).willReturn(null);
        givenProfile(profile);

        OnboardingStatusResponse response = onboardingService.getOnboardingStatus(USER_ID);

        assertThat(response.onboardingNextStep()).isEqualTo(OnboardingStep.REGION);
        assertThat(response.requirements().regionComplete()).isFalse();
    }

    @Test
    void 직업이_공백이면_다음_단계는_PROFILE이다() {
        givenOnboardingUser();
        Profile profile = completeProfile();
        given(profile.getJob()).willReturn(" ");
        givenProfile(profile);

        OnboardingStatusResponse response = onboardingService.getOnboardingStatus(USER_ID);

        assertThat(response.onboardingNextStep()).isEqualTo(OnboardingStep.PROFILE);
        assertThat(response.requirements().basicInfoComplete()).isFalse();
    }

    @Test
    void 라이프스타일이_하나라도_없으면_다음_단계는_LIFESTYLE이다() {
        givenOnboardingUser();
        Profile profile = completeProfile();
        given(profile.getReligion()).willReturn(null);
        givenProfile(profile);

        OnboardingStatusResponse response = onboardingService.getOnboardingStatus(USER_ID);

        assertThat(response.onboardingNextStep()).isEqualTo(OnboardingStep.LIFESTYLE);
        assertThat(response.requirements().lifestyleComplete()).isFalse();
    }

    @Test
    void MBTI가_없으면_다음_단계는_MBTI다() {
        givenOnboardingUser();
        Profile profile = completeProfile();
        given(profile.getMbti()).willReturn(null);
        givenProfile(profile);

        OnboardingStatusResponse response = onboardingService.getOnboardingStatus(USER_ID);

        assertThat(response.onboardingNextStep()).isEqualTo(OnboardingStep.MBTI);
        assertThat(response.requirements().mbtiComplete()).isFalse();
    }

    @Test
    void 모든_온보딩_정보가_있으면_다음_단계는_COMPLETE이다() {
        givenOnboardingUser();
        givenProfile(completeProfile());

        OnboardingStatusResponse response = onboardingService.getOnboardingStatus(USER_ID);

        assertThat(response.onboardingNextStep()).isEqualTo(OnboardingStep.COMPLETE);
        assertThat(response.requirements()).isEqualTo(OnboardingRequirementsResponse.complete());
    }

    @Test
    void ACTIVE_회원은_프로필을_조회하지_않고_COMPLETE를_반환한다() {
        User user = user(UserStatus.ACTIVE);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

        OnboardingStatusResponse response = onboardingService.getOnboardingStatus(USER_ID);

        assertThat(response.userStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(response.onboardingNextStep()).isEqualTo(OnboardingStep.COMPLETE);
        assertThat(response.requirements()).isEqualTo(OnboardingRequirementsResponse.complete());
        verifyNoInteractions(profileRepository);
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"SUSPENDED", "WITHDRAWN"})
    void SUSPENDED_또는_WITHDRAWN_회원이면_접근_예외가_발생한다(UserStatus userStatus) {
        User user = user(userStatus);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

        assertThatThrownBy(() -> onboardingService.getOnboardingStatus(USER_ID))
            .isInstanceOf(OnboardingAccessNotAllowedException.class);

        verifyNoInteractions(profileRepository);
    }

    @Test
    void 회원이_없으면_사용자_없음_예외가_발생한다() {
        given(userRepository.findById(USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> onboardingService.getOnboardingStatus(USER_ID))
            .isInstanceOf(UserNotFoundException.class);
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"ONBOARDING", "ACTIVE"})
    void 온보딩_중이거나_활성_회원이면_기본_정보를_조회할_수_있다(UserStatus userStatus) {
        User user = user(userStatus);
        LocalDate birthDate = LocalDate.of(1990, 5, 21);
        given(user.getId()).willReturn(USER_ID);
        given(user.getBirthDate()).willReturn(birthDate);
        given(user.getGender()).willReturn(Gender.FEMALE);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

        OnboardingProfileResponse response = onboardingService.getOnboardingProfile(USER_ID);

        assertThat(response.userId()).isEqualTo(USER_ID);
        assertThat(response.birthDate()).isEqualTo(birthDate);
        assertThat(response.gender()).isEqualTo(Gender.FEMALE);
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"SUSPENDED", "WITHDRAWN"})
    void 정지_또는_탈퇴_회원이면_기본_정보_조회_예외가_발생한다(UserStatus userStatus) {
        User user = user(userStatus);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

        assertThatThrownBy(() -> onboardingService.getOnboardingProfile(USER_ID))
            .isInstanceOf(OnboardingAccessNotAllowedException.class);
    }

    @Test
    void 회원이_없으면_기본_정보_조회_시_사용자_없음_예외가_발생한다() {
        given(userRepository.findById(USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> onboardingService.getOnboardingProfile(USER_ID))
            .isInstanceOf(UserNotFoundException.class);
    }

    private void givenOnboardingUser() {
        User user = user(UserStatus.ONBOARDING);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
    }

    private void givenProfile(Profile profile) {
        given(profileRepository.findByUserIdAndDeletedAtIsNull(USER_ID))
            .willReturn(Optional.of(profile));
    }

    private User user(UserStatus status) {
        User user = mock(User.class);
        given(user.getStatus()).willReturn(status);
        return user;
    }

    private Profile completeProfile() {
        Profile profile = mock(Profile.class);
        ActivityRegion activityRegion = mock(ActivityRegion.class);
        given(profile.getNickname()).willReturn("하리");
        given(profile.getActivityRegion()).willReturn(activityRegion);
        given(profile.getHeight()).willReturn((short) 175);
        given(profile.getBodyType()).willReturn(BodyType.AVERAGE);
        given(profile.getEducationLevel()).willReturn(EducationLevel.BACHELOR);
        given(profile.getJob()).willReturn("개발자");
        given(profile.getReligion()).willReturn(Religion.NONE);
        given(profile.getDrinking()).willReturn(Drinking.SOCIAL);
        given(profile.getSmoking()).willReturn(Smoking.NON_SMOKER);
        given(profile.getMbti()).willReturn(Mbti.INTJ);
        return profile;
    }
}
