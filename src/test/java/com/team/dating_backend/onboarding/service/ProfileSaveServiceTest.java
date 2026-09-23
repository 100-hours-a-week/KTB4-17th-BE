package com.team.dating_backend.onboarding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.team.dating_backend.activityregion.entity.ActivityRegion;
import com.team.dating_backend.activityregion.repository.ActivityRegionRepository;
import com.team.dating_backend.common.exception.RequestValidationException;
import com.team.dating_backend.onboarding.dto.request.ProfileSaveRequest;
import com.team.dating_backend.onboarding.dto.response.ProfileSaveResult;
import com.team.dating_backend.onboarding.exception.NicknameAlreadyInUseException;
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
import com.team.dating_backend.user.repository.UserRepository;
import java.sql.SQLException;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class ProfileSaveServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long ACTIVITY_REGION_ID = 37L;
    private static final String NICKNAME = "하리";

    private UserRepository userRepository;
    private ProfileRepository profileRepository;
    private ActivityRegionRepository activityRegionRepository;
    private ProfileSaveService profileSaveService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        profileRepository = mock(ProfileRepository.class);
        activityRegionRepository = mock(ActivityRegionRepository.class);
        profileSaveService =
                new ProfileSaveService(userRepository, profileRepository, activityRegionRepository);
    }

    @Test
    void 최초_부분_입력을_저장하면_프로필을_생성하고_미선택_필드는_null로_저장한다() {
        User user = mock(User.class);
        ActivityRegion activityRegion = mock(ActivityRegion.class);
        given(activityRegion.getId()).willReturn(ACTIVITY_REGION_ID);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
        given(profileRepository.findByUserIdAndDeletedAtIsNull(USER_ID))
                .willReturn(Optional.empty());
        given(profileRepository.existsByNicknameAndDeletedAtIsNullAndUserIdNot(NICKNAME, USER_ID))
                .willReturn(false);
        given(activityRegionRepository.findById(ACTIVITY_REGION_ID))
                .willReturn(Optional.of(activityRegion));
        given(profileRepository.saveAndFlush(any(Profile.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        ProfileSaveResult result =
                profileSaveService.saveProfile(
                        USER_ID, partialRequest(ACTIVITY_REGION_ID, NICKNAME));

        assertThat(result.created()).isTrue();
        assertThat(result.response().profile().activityRegionId()).isEqualTo(ACTIVITY_REGION_ID);
        assertThat(result.response().profile().nickname()).isEqualTo(NICKNAME);
        assertThat(result.response().profile().height()).isNull();
        assertThat(result.response().profile().bodyType()).isNull();
        assertThat(result.response().profile().educationLevel()).isNull();
        assertThat(result.response().profile().job()).isNull();
        assertThat(result.response().profile().religion()).isNull();
        assertThat(result.response().profile().mbti()).isNull();
        assertThat(result.response().profile().drinking()).isNull();
        assertThat(result.response().profile().smoking()).isNull();
    }

    @Test
    void 기존_프로필을_수정하면_요청에서_누락된_기존_값을_null로_덮어쓴다() {
        User user = mock(User.class);
        Profile profile = completeProfile(user);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
        given(profileRepository.findByUserIdAndDeletedAtIsNull(USER_ID))
                .willReturn(Optional.of(profile));
        given(profileRepository.saveAndFlush(profile)).willReturn(profile);

        ProfileSaveResult result =
                profileSaveService.saveProfile(USER_ID, partialRequest(null, null));

        assertThat(result.created()).isFalse();
        assertThat(profile.getActivityRegion()).isNull();
        assertThat(profile.getNickname()).isNull();
        assertThat(profile.getHeight()).isNull();
        assertThat(profile.getBodyType()).isNull();
        assertThat(profile.getEducationLevel()).isNull();
        assertThat(profile.getJob()).isNull();
        assertThat(profile.getReligion()).isNull();
        assertThat(profile.getMbti()).isNull();
        assertThat(profile.getDrinking()).isNull();
        assertThat(profile.getSmoking()).isNull();
        assertThat(result.response().profile().nickname()).isNull();
    }

    @Test
    void 같은_사용자의_기존_닉네임은_다시_저장할_수_있다() {
        User user = mock(User.class);
        Profile profile = completeProfile(user);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
        given(profileRepository.findByUserIdAndDeletedAtIsNull(USER_ID))
                .willReturn(Optional.of(profile));
        given(profileRepository.existsByNicknameAndDeletedAtIsNullAndUserIdNot(NICKNAME, USER_ID))
                .willReturn(false);
        given(profileRepository.saveAndFlush(profile)).willReturn(profile);

        profileSaveService.saveProfile(USER_ID, partialRequest(null, NICKNAME));

        assertThat(profile.getNickname()).isEqualTo(NICKNAME);
        verify(profileRepository).existsByNicknameAndDeletedAtIsNullAndUserIdNot(NICKNAME, USER_ID);
    }

    @Test
    void 다른_활성_프로필이_닉네임을_사용하면_중복_예외가_발생한다() {
        User user = mock(User.class);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
        given(profileRepository.findByUserIdAndDeletedAtIsNull(USER_ID))
                .willReturn(Optional.empty());
        given(profileRepository.existsByNicknameAndDeletedAtIsNullAndUserIdNot(NICKNAME, USER_ID))
                .willReturn(true);

        assertThatThrownBy(
                        () ->
                                profileSaveService.saveProfile(
                                        USER_ID, partialRequest(null, NICKNAME)))
                .isInstanceOf(NicknameAlreadyInUseException.class);

        verify(profileRepository).existsByNicknameAndDeletedAtIsNullAndUserIdNot(NICKNAME, USER_ID);
        verifyNoInteractions(activityRegionRepository);
    }

    @Test
    void 저장_중_활성_닉네임_UNIQUE_인덱스가_충돌하면_중복_예외가_발생한다() {
        User user = mock(User.class);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
        given(profileRepository.findByUserIdAndDeletedAtIsNull(USER_ID))
                .willReturn(Optional.empty());
        given(profileRepository.existsByNicknameAndDeletedAtIsNullAndUserIdNot(NICKNAME, USER_ID))
                .willReturn(false);
        given(profileRepository.saveAndFlush(any(Profile.class)))
                .willThrow(dataIntegrityViolationException("profiles.uk_profiles_active_nickname"));

        assertThatThrownBy(
                        () ->
                                profileSaveService.saveProfile(
                                        USER_ID, partialRequest(null, NICKNAME)))
                .isInstanceOf(NicknameAlreadyInUseException.class);
    }

    @Test
    void 저장_중_다른_무결성_제약이_충돌하면_원래_예외가_발생한다() {
        User user = mock(User.class);
        DataIntegrityViolationException exception =
                dataIntegrityViolationException("uk_profiles_user_id");
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
        given(profileRepository.findByUserIdAndDeletedAtIsNull(USER_ID))
                .willReturn(Optional.empty());
        given(profileRepository.existsByNicknameAndDeletedAtIsNullAndUserIdNot(NICKNAME, USER_ID))
                .willReturn(false);
        given(profileRepository.saveAndFlush(any(Profile.class))).willThrow(exception);

        assertThatThrownBy(
                        () ->
                                profileSaveService.saveProfile(
                                        USER_ID, partialRequest(null, NICKNAME)))
                .isSameAs(exception);
    }

    @Test
    void 존재하지_않는_활동_지역이면_필드_검증_예외가_발생한다() {
        User user = mock(User.class);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
        given(profileRepository.findByUserIdAndDeletedAtIsNull(USER_ID))
                .willReturn(Optional.empty());
        given(activityRegionRepository.findById(ACTIVITY_REGION_ID)).willReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                profileSaveService.saveProfile(
                                        USER_ID, partialRequest(ACTIVITY_REGION_ID, null)))
                .isInstanceOfSatisfying(
                        RequestValidationException.class,
                        exception -> {
                            assertThat(exception.getErrors()).hasSize(1);
                            assertThat(exception.getErrors().getFirst().field())
                                    .isEqualTo("activityRegionId");
                            assertThat(exception.getErrors().getFirst().reason())
                                    .isEqualTo("must reference an existing activity region");
                        });
    }

    @Test
    void 사용자가_존재하지_않으면_사용자_없음_예외가_발생한다() {
        given(userRepository.findById(USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(
                        () -> profileSaveService.saveProfile(USER_ID, partialRequest(null, null)))
                .isInstanceOf(UserNotFoundException.class);

        verifyNoInteractions(profileRepository, activityRegionRepository);
    }

    private ProfileSaveRequest partialRequest(Long activityRegionId, String nickname) {
        return new ProfileSaveRequest(
                nickname, activityRegionId, null, null, null, null, null, null, null, null);
    }

    private Profile completeProfile(User user) {
        Profile profile = new Profile(user);
        profile.updateProfile(
                mock(ActivityRegion.class),
                NICKNAME,
                (short) 175,
                BodyType.AVERAGE,
                EducationLevel.BACHELOR,
                "개발자",
                Religion.NONE,
                Mbti.INTJ,
                Drinking.OCCASIONAL,
                Smoking.NON_SMOKER);
        return profile;
    }

    private DataIntegrityViolationException dataIntegrityViolationException(String constraintName) {
        ConstraintViolationException constraintViolation =
                new ConstraintViolationException(
                        "constraint violation",
                        new SQLException(),
                        ConstraintViolationException.ConstraintKind.UNIQUE,
                        constraintName);
        return new DataIntegrityViolationException("data integrity violation", constraintViolation);
    }
}
