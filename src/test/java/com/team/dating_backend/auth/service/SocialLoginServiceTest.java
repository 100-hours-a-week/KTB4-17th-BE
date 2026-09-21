package com.team.dating_backend.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.team.dating_backend.auth.dto.SocialLoginResult;
import com.team.dating_backend.auth.entity.UserAuthAccount;
import com.team.dating_backend.auth.enums.AuthProvider;
import com.team.dating_backend.auth.enums.LoginDestination;
import com.team.dating_backend.auth.repository.UserAuthAccountRepository;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.UserStatus;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SocialLoginServiceTest {

    private static final String KAKAO_PROVIDER_USER_ID = "kakao-123";

    @Mock private UserAuthAccountRepository userAuthAccountRepository;

    @InjectMocks private SocialLoginService socialLoginService;

    @Test
    void ACTIVE_회원이_카카오로_로그인하면_서비스_진입_결과를_반환한다() {
        // given
        UserAuthAccount account = linkedAccount(UserStatus.ACTIVE, 1L);
        given(
                        userAuthAccountRepository.findByProviderAndProviderUserId(
                                AuthProvider.KAKAO, KAKAO_PROVIDER_USER_ID))
                .willReturn(Optional.of(account));

        // when
        SocialLoginResult result =
                socialLoginService.login(AuthProvider.KAKAO, KAKAO_PROVIDER_USER_ID);

        // then
        SocialLoginResult.Authenticated authenticated =
                assertInstanceOf(SocialLoginResult.Authenticated.class, result);
        assertEquals(1L, authenticated.userId());
        assertEquals(LoginDestination.SERVICE, authenticated.destination());
        verify(userAuthAccountRepository)
                .findByProviderAndProviderUserId(AuthProvider.KAKAO, KAKAO_PROVIDER_USER_ID);
    }

    @Test
    void ONBOARDING_회원이_카카오로_로그인하면_온보딩_진입_결과를_반환한다() {
        // given
        UserAuthAccount account = linkedAccount(UserStatus.ONBOARDING, 2L);
        given(
                        userAuthAccountRepository.findByProviderAndProviderUserId(
                                AuthProvider.KAKAO, KAKAO_PROVIDER_USER_ID))
                .willReturn(Optional.of(account));

        // when
        SocialLoginResult result =
                socialLoginService.login(AuthProvider.KAKAO, KAKAO_PROVIDER_USER_ID);

        // then
        SocialLoginResult.Authenticated authenticated =
                assertInstanceOf(SocialLoginResult.Authenticated.class, result);
        assertEquals(2L, authenticated.userId());
        assertEquals(LoginDestination.ONBOARDING, authenticated.destination());
    }

    @Test
    void 연결된_인증_계정이_없으면_신규_회원가입_결과를_반환한다() {
        // given
        given(
                        userAuthAccountRepository.findByProviderAndProviderUserId(
                                AuthProvider.KAKAO, KAKAO_PROVIDER_USER_ID))
                .willReturn(Optional.empty());

        // when
        SocialLoginResult result =
                socialLoginService.login(AuthProvider.KAKAO, KAKAO_PROVIDER_USER_ID);

        // then
        SocialLoginResult.PendingRegistration pending =
                assertInstanceOf(SocialLoginResult.PendingRegistration.class, result);
        assertEquals(AuthProvider.KAKAO, pending.provider());
        assertEquals(KAKAO_PROVIDER_USER_ID, pending.providerUserId());
    }

    @Test
    void WITHDRAWN_회원이_카카오로_로그인하면_재가입_회원가입_결과를_반환한다() {
        // given
        UserAuthAccount account = linkedAccount(UserStatus.WITHDRAWN, null);
        given(
                        userAuthAccountRepository.findByProviderAndProviderUserId(
                                AuthProvider.KAKAO, KAKAO_PROVIDER_USER_ID))
                .willReturn(Optional.of(account));

        // when
        SocialLoginResult result =
                socialLoginService.login(AuthProvider.KAKAO, KAKAO_PROVIDER_USER_ID);

        // then
        SocialLoginResult.PendingRegistration pending =
                assertInstanceOf(SocialLoginResult.PendingRegistration.class, result);
        assertEquals(AuthProvider.KAKAO, pending.provider());
        assertEquals(KAKAO_PROVIDER_USER_ID, pending.providerUserId());
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
