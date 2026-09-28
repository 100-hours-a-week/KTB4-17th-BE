package com.team.dating_backend.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.team.dating_backend.auth.dto.PendingRegistrationTokenPayload;
import com.team.dating_backend.auth.entity.UserAuthAccount;
import com.team.dating_backend.auth.enums.AuthProvider;
import com.team.dating_backend.auth.exception.PendingRegistrationAccessDeniedException;
import com.team.dating_backend.auth.repository.UserAuthAccountRepository;
import com.team.dating_backend.auth.service.JwtService;
import com.team.dating_backend.user.dto.request.UserRegistrationRequest;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.Gender;
import com.team.dating_backend.user.enums.UserStatus;
import com.team.dating_backend.user.repository.UserRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserRegistrationServiceTest {

    private static final String PENDING_TOKEN = "pending-token";
    private static final String PROVIDER_USER_ID = "kakao-123";
    private static final String ACCESS_TOKEN = "access-token";

    @Mock
    private JwtService jwtService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserAuthAccountRepository userAuthAccountRepository;

    @InjectMocks
    private UserRegistrationService userRegistrationService;

    @Test
    void 신규_인증_계정이면_User와_UserAuthAccount를_생성한다() {
        // given
        givenPendingPayload();
        given(
            userAuthAccountRepository.findForUpdateByProviderAndProviderUserId(
                AuthProvider.KAKAO, PROVIDER_USER_ID))
            .willReturn(Optional.empty());
        User savedUser = savedUser(10L);
        given(userRepository.save(any(User.class))).willReturn(savedUser);
        given(jwtService.createServiceAuthToken(10L)).willReturn(ACCESS_TOKEN);

        // when
        String result = userRegistrationService.registerUser(PENDING_TOKEN, registrationRequest());

        // then
        assertEquals(ACCESS_TOKEN, result);
        verify(userRepository).save(any(User.class));
        verify(userAuthAccountRepository).save(any(UserAuthAccount.class));
        verify(jwtService).createServiceAuthToken(10L);
    }

    @Test
    void WITHDRAWN_회원이_재가입하면_기존_인증_계정을_새_User에게_연결한다() {
        // given
        givenPendingPayload();
        UserAuthAccount existingAccount = accountLinkedTo(UserStatus.WITHDRAWN);
        given(
            userAuthAccountRepository.findForUpdateByProviderAndProviderUserId(
                AuthProvider.KAKAO, PROVIDER_USER_ID))
            .willReturn(Optional.of(existingAccount));
        User savedUser = savedUser(20L);
        given(userRepository.save(any(User.class))).willReturn(savedUser);
        given(jwtService.createServiceAuthToken(20L)).willReturn(ACCESS_TOKEN);

        // when
        String result = userRegistrationService.registerUser(PENDING_TOKEN, registrationRequest());

        // then
        assertEquals(ACCESS_TOKEN, result);
        verify(existingAccount).relink(eq(savedUser), any(LocalDateTime.class));
        verify(userAuthAccountRepository).save(existingAccount);
        verify(jwtService).createServiceAuthToken(20L);
    }

    @Test
    void 이미_연결된_ACTIVE_회원은_Pending_토큰으로_새_회원이_될_수_없다() {
        // given
        givenPendingPayload();
        UserAuthAccount existingAccount = accountLinkedTo(UserStatus.ACTIVE);
        given(
            userAuthAccountRepository.findForUpdateByProviderAndProviderUserId(
                AuthProvider.KAKAO, PROVIDER_USER_ID))
            .willReturn(Optional.of(existingAccount));

        // when & then
        assertThrows(
            PendingRegistrationAccessDeniedException.class,
            () -> userRegistrationService.registerUser(PENDING_TOKEN, registrationRequest()));
        verify(userRepository, never()).save(any(User.class));
        verify(userAuthAccountRepository, never()).save(any(UserAuthAccount.class));
    }

    private void givenPendingPayload() {
        given(jwtService.parsePendingRegistrationToken(PENDING_TOKEN))
            .willReturn(
                new PendingRegistrationTokenPayload(AuthProvider.KAKAO, PROVIDER_USER_ID));
    }

    private User savedUser(Long userId) {
        User user = mock(User.class);
        given(user.getId()).willReturn(userId);
        return user;
    }

    private UserAuthAccount accountLinkedTo(UserStatus status) {
        User user = mock(User.class);
        given(user.getStatus()).willReturn(status);

        UserAuthAccount account = mock(UserAuthAccount.class);
        given(account.getUser()).willReturn(user);
        return account;
    }

    private UserRegistrationRequest registrationRequest() {
        return new UserRegistrationRequest("우", LocalDate.of(2000, 1, 1), Gender.MALE);
    }
}
