package com.team.dating_backend.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.team.dating_backend.auth.entity.UserAuthAccount;
import com.team.dating_backend.auth.enums.AuthProvider;
import com.team.dating_backend.auth.repository.UserAuthAccountRepository;
import com.team.dating_backend.file.entity.File;
import com.team.dating_backend.profile.entity.Profile;
import com.team.dating_backend.profile.entity.ProfileImage;
import com.team.dating_backend.profile.repository.ProfileImageRepository;
import com.team.dating_backend.profile.repository.ProfileRepository;
import com.team.dating_backend.security.AuthenticationRequiredException;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.Gender;
import com.team.dating_backend.user.enums.UserStatus;
import com.team.dating_backend.user.repository.UserRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class UserWithdrawalServiceTest {

    private static final Long USER_ID = Long.MAX_VALUE;

    private UserRepository userRepository;
    private UserAuthAccountRepository authAccountRepository;
    private ProfileRepository profileRepository;
    private ProfileImageRepository profileImageRepository;
    private UserWithdrawalService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        authAccountRepository = mock(UserAuthAccountRepository.class);
        profileRepository = mock(ProfileRepository.class);
        profileImageRepository = mock(ProfileImageRepository.class);
        service = new UserWithdrawalService(userRepository, authAccountRepository,
            profileRepository, profileImageRepository);
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"ACTIVE", "ONBOARDING", "SUSPENDED"})
    void 탈퇴하면_개인정보와_인증정보를_비식별화하고_프로필을_논리_삭제한다(UserStatus status) {
        User user = user(status);
        UserAuthAccount account = UserAuthAccount.create(
            user, AuthProvider.KAKAO, "kakao-original", LocalDateTime.now());
        Profile profile = new Profile(user);
        ReflectionTestUtils.setField(profile, "id", 10L);
        ProfileImage image = new ProfileImage(profile, mock(File.class), (short) 1, true);
        given(userRepository.findByIdForUpdate(USER_ID)).willReturn(Optional.of(user));
        given(authAccountRepository.findAllByUserId(USER_ID)).willReturn(List.of(account));
        given(profileRepository.findByUserIdAndDeletedAtIsNull(USER_ID))
            .willReturn(Optional.of(profile));
        given(profileImageRepository.findByProfileIdAndDeletedAtIsNull(10L))
            .willReturn(List.of(image));

        service.withdraw(USER_ID);

        assertThat(user.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
        assertThat(user.getName()).isEqualTo("탈퇴" + USER_ID).hasSize(21);
        assertThat(user.getBirthDate()).isEqualTo(LocalDate.of(1000, 1, 1));
        assertThat(user.getWithdrawnAt()).isNotNull().isEqualTo(user.getUpdatedAt());
        assertThat(account.getProviderUserId()).isEqualTo("withdrawn" + USER_ID);
        assertThat(profile.getDeletedAt()).isEqualTo(user.getWithdrawnAt());
        assertThat(image.getDeletedAt()).isEqualTo(user.getWithdrawnAt());
        verify(userRepository).findByIdForUpdate(USER_ID);
    }

    @Test
    void 활성_프로필이_없는_사용자도_WITHDRAWN_상태로_변경한다() {
        User user = user(UserStatus.ACTIVE);
        given(userRepository.findByIdForUpdate(USER_ID)).willReturn(Optional.of(user));
        given(authAccountRepository.findAllByUserId(USER_ID)).willReturn(List.of());
        given(profileRepository.findByUserIdAndDeletedAtIsNull(USER_ID))
            .willReturn(Optional.empty());

        service.withdraw(USER_ID);

        assertThat(user.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
        verifyNoInteractions(profileImageRepository);
    }

    @Test
    void 이미_WITHDRAWN인_사용자는_인증계정과_프로필_변경을_처리하지_않는다() {
        User user = user(UserStatus.ACTIVE);
        user.withdraw(LocalDateTime.of(2026, 10, 13, 12, 0));
        given(userRepository.findByIdForUpdate(USER_ID)).willReturn(Optional.of(user));

        service.withdraw(USER_ID);

        verifyNoInteractions(authAccountRepository, profileRepository, profileImageRepository);
    }

    @Test
    void 사용자가_없으면_인증오류를_반환한다() {
        given(userRepository.findByIdForUpdate(USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.withdraw(USER_ID))
            .isInstanceOf(AuthenticationRequiredException.class);
    }

    private User user(UserStatus status) {
        User user = User.create("기존이름", LocalDate.of(2000, 1, 1), Gender.MALE,
            LocalDateTime.now());
        ReflectionTestUtils.setField(user, "id", USER_ID);
        ReflectionTestUtils.setField(user, "status", status);
        return user;
    }
}
