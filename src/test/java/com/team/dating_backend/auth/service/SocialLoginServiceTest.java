package com.team.dating_backend.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.team.dating_backend.auth.dto.SocialLoginResult;
import com.team.dating_backend.auth.entity.UserAuthAccount;
import com.team.dating_backend.auth.enums.AuthErrorCode;
import com.team.dating_backend.auth.enums.AuthProvider;
import com.team.dating_backend.auth.enums.LoginDestination;
import com.team.dating_backend.auth.repository.UserAuthAccountRepository;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.FaceVerificationStatus;
import com.team.dating_backend.user.enums.UserStatus;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SocialLoginServiceTest {

    private static final String KAKAO_PROVIDER_USER_ID = "kakao-123";
    private static final String KAKAO_TALK_NICKNAME = "Alice";

    @Mock
    private UserAuthAccountRepository userAuthAccountRepository;

    @InjectMocks
    private SocialLoginService socialLoginService;

    @Test
    void ACTIVE_회원이_카카오로_로그인하면_서비스_진입_결과를_반환한다() {
        // given
        UserAuthAccount account = linkedAccount(UserStatus.ACTIVE, 1L);
        User user = account.getUser();
        given(user.getFaceVerificationStatus()).willReturn(FaceVerificationStatus.VERIFIED);
        given(
            userAuthAccountRepository.findByProviderAndProviderUserId(
                AuthProvider.KAKAO, KAKAO_PROVIDER_USER_ID))
            .willReturn(Optional.of(account));

        // when
        SocialLoginResult result = socialLoginService.login(AuthProvider.KAKAO,
            KAKAO_PROVIDER_USER_ID, KAKAO_TALK_NICKNAME);

        // then
        SocialLoginResult.Authenticated authenticated = assertInstanceOf(
            SocialLoginResult.Authenticated.class, result);
        assertEquals(1L, authenticated.userId());
        assertEquals(LoginDestination.SERVICE, authenticated.destination());
        verify(user).updateName(eq(KAKAO_TALK_NICKNAME), any(LocalDateTime.class));
        verify(userAuthAccountRepository)
            .findByProviderAndProviderUserId(AuthProvider.KAKAO, KAKAO_PROVIDER_USER_ID);
    }

    @Test
    void 정면_사진_인증이_미완료된_ACTIVE_회원은_로그인_후_온보딩으로_이동한다() {
        UserAuthAccount account = linkedAccount(UserStatus.ACTIVE, 1L);
        User user = account.getUser();
        given(user.getFaceVerificationStatus()).willReturn(FaceVerificationStatus.NOT_VERIFIED);
        given(
            userAuthAccountRepository.findByProviderAndProviderUserId(
                AuthProvider.KAKAO, KAKAO_PROVIDER_USER_ID))
            .willReturn(Optional.of(account));

        SocialLoginResult result = socialLoginService.login(
            AuthProvider.KAKAO, KAKAO_PROVIDER_USER_ID, KAKAO_TALK_NICKNAME);

        SocialLoginResult.Authenticated authenticated = assertInstanceOf(
            SocialLoginResult.Authenticated.class, result);
        assertEquals(LoginDestination.ONBOARDING, authenticated.destination());
        verify(user).updateName(eq(KAKAO_TALK_NICKNAME), any(LocalDateTime.class));
    }

    @Test
    void ONBOARDING_회원이_카카오로_로그인하면_온보딩_진입_결과를_반환한다() {
        // given
        UserAuthAccount account = linkedAccount(UserStatus.ONBOARDING, 2L);
        User user = account.getUser();
        given(
            userAuthAccountRepository.findByProviderAndProviderUserId(
                AuthProvider.KAKAO, KAKAO_PROVIDER_USER_ID))
            .willReturn(Optional.of(account));

        // when
        SocialLoginResult result = socialLoginService.login(AuthProvider.KAKAO,
            KAKAO_PROVIDER_USER_ID, KAKAO_TALK_NICKNAME);

        // then
        SocialLoginResult.Authenticated authenticated = assertInstanceOf(
            SocialLoginResult.Authenticated.class, result);
        assertEquals(2L, authenticated.userId());
        assertEquals(LoginDestination.ONBOARDING, authenticated.destination());
        verify(user).updateName(eq(KAKAO_TALK_NICKNAME), any(LocalDateTime.class));
    }

    @Test
    void 연결된_인증_계정이_없으면_신규_회원가입_결과를_반환한다() {
        // given
        given(
            userAuthAccountRepository.findByProviderAndProviderUserId(
                AuthProvider.KAKAO, KAKAO_PROVIDER_USER_ID))
            .willReturn(Optional.empty());

        // when
        SocialLoginResult result = socialLoginService.login(AuthProvider.KAKAO,
            KAKAO_PROVIDER_USER_ID, KAKAO_TALK_NICKNAME);

        // then
        SocialLoginResult.PendingRegistration pending = assertInstanceOf(
            SocialLoginResult.PendingRegistration.class,
            result);
        assertEquals(AuthProvider.KAKAO, pending.provider());
        assertEquals(KAKAO_PROVIDER_USER_ID, pending.providerUserId());
        assertEquals(KAKAO_TALK_NICKNAME, pending.kakaoTalkNickname());
    }

    @Test
    void WITHDRAWN_회원이_카카오로_로그인하면_재가입_회원가입_결과를_반환한다() {
        // given
        UserAuthAccount account = linkedAccount(UserStatus.WITHDRAWN, null);
        User user = account.getUser();
        given(
            userAuthAccountRepository.findByProviderAndProviderUserId(
                AuthProvider.KAKAO, KAKAO_PROVIDER_USER_ID))
            .willReturn(Optional.of(account));

        // when
        SocialLoginResult result = socialLoginService.login(AuthProvider.KAKAO,
            KAKAO_PROVIDER_USER_ID, KAKAO_TALK_NICKNAME);

        // then
        SocialLoginResult.PendingRegistration pending = assertInstanceOf(
            SocialLoginResult.PendingRegistration.class,
            result);
        assertEquals(AuthProvider.KAKAO, pending.provider());
        assertEquals(KAKAO_PROVIDER_USER_ID, pending.providerUserId());
        assertEquals(KAKAO_TALK_NICKNAME, pending.kakaoTalkNickname());
        verify(user, never()).updateName(any(), any(LocalDateTime.class));
    }

    @Test
    void SUSPENDED_사용자는_소셜_로그인을_지원하지_않는다() {
        UserAuthAccount account = linkedAccount(UserStatus.SUSPENDED, null);
        User user = account.getUser();
        given(
            userAuthAccountRepository.findByProviderAndProviderUserId(
                AuthProvider.KAKAO, KAKAO_PROVIDER_USER_ID))
            .willReturn(Optional.of(account));

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> socialLoginService.login(
                AuthProvider.KAKAO, KAKAO_PROVIDER_USER_ID, KAKAO_TALK_NICKNAME));

        assertEquals(
            AuthErrorCode.SOCIAL_LOGIN_USER_STATUS_NOT_SUPPORTED.name(),
            exception.getMessage());
        verify(user, never()).updateName(any(), any(LocalDateTime.class));
    }

    private UserAuthAccount linkedAccount(UserStatus status, Long userId) {
        User user = mock(User.class);
        if (userId != null) {
            given(user.getId()).willReturn(userId);
        }
        given(user.getStatus()).willReturn(status);

        UserAuthAccount account = mock(UserAuthAccount.class);
        given(account.getUser()).willReturn(user);
        return account;
    }
}
