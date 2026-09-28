package com.team.dating_backend.auth.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.team.dating_backend.auth.config.AuthWebProperties;
import com.team.dating_backend.auth.config.JwtProperties;
import com.team.dating_backend.auth.dto.SocialLoginResult;
import com.team.dating_backend.auth.enums.AuthProvider;
import com.team.dating_backend.auth.enums.LoginDestination;
import com.team.dating_backend.auth.service.JwtService;
import com.team.dating_backend.auth.service.OAuthLoginCodeService;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class AuthLoginResponseFactoryTest {

    private static final String SERVICE_REDIRECT_URI = "https://frontend.example.com/service";
    private static final String REGISTRATION_REDIRECT_URI = "https://frontend.example.com/registration";
    private static final String ONBOARDING_REDIRECT_URI = "https://frontend.example.com/onboarding";

    @Mock
    private JwtService jwtService;

    @Mock
    private OAuthLoginCodeService oauthLoginCodeService;

    private AuthLoginResponseFactory authLoginResponseFactory;

    @BeforeEach
    void setUp() {
        AuthWebProperties authWebProperties = new AuthWebProperties();
        authWebProperties.setServiceRedirectUri(SERVICE_REDIRECT_URI);
        authWebProperties.setRegistrationRedirectUri(REGISTRATION_REDIRECT_URI);
        authWebProperties.setOnboardingRedirectUri(ONBOARDING_REDIRECT_URI);
        authWebProperties.setSecureCookie(true);

        JwtProperties jwtProperties = new JwtProperties();
        jwtProperties.setServiceExpirationMinutes(60);
        jwtProperties.setPendingExpirationMinutes(10);

        AuthCookieFactory authCookieFactory = new AuthCookieFactory(authWebProperties,
            jwtProperties);
        authLoginResponseFactory = new AuthLoginResponseFactory(
            jwtService, oauthLoginCodeService, authCookieFactory, authWebProperties);
    }

    @Test
    void ACTIVE_회원은_일회용_로그인_코드와_서비스_목적지로_리다이렉트된다() {
        // given
        HttpSession session = org.mockito.Mockito.mock(HttpSession.class);
        given(jwtService.createServiceAuthToken(1L)).willReturn("service-token");
        given(oauthLoginCodeService.issue("service-token", session)).willReturn("one-time-code");
        SocialLoginResult loginResult = new SocialLoginResult.Authenticated(1L,
            LoginDestination.SERVICE);

        // when
        ResponseEntity<Void> response = authLoginResponseFactory.create(loginResult, session);

        // then
        assertEquals(HttpStatus.FOUND, response.getStatusCode());
        assertEquals(
            SERVICE_REDIRECT_URI + "#auth_code=one-time-code",
            response.getHeaders().getLocation().toString());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals("no-referrer", response.getHeaders().getFirst("Referrer-Policy"));

        List<String> setCookieHeaders = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertTrue(setCookieHeaders.getFirst().contains("ACCESS_TOKEN="));
        assertTrue(setCookieHeaders.getFirst().contains("Max-Age=0"));
        assertTrue(setCookieHeaders.get(1).contains("PENDING_REGISTRATION_TOKEN="));
        assertTrue(setCookieHeaders.get(1).contains("Max-Age=0"));
        org.mockito.Mockito.verify(oauthLoginCodeService).issue("service-token", session);
        verify(jwtService).createServiceAuthToken(1L);
        verifyNoMoreInteractions(jwtService);
    }

    @Test
    void 신규_회원은_PENDING_REGISTRATION_TOKEN과_회원가입_목적지로_리다이렉트된다() {
        // given
        given(jwtService.createPendingRegistrationToken(AuthProvider.KAKAO, "kakao-123"))
            .willReturn("pending-token");
        SocialLoginResult loginResult = new SocialLoginResult.PendingRegistration(
            AuthProvider.KAKAO, "kakao-123");

        // when
        ResponseEntity<Void> response = authLoginResponseFactory.create(
            loginResult, org.mockito.Mockito.mock(HttpSession.class));

        // then
        assertEquals(HttpStatus.FOUND, response.getStatusCode());
        assertEquals(REGISTRATION_REDIRECT_URI, response.getHeaders().getLocation().toString());

        List<String> setCookieHeaders = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertTrue(
            setCookieHeaders.getFirst().contains("PENDING_REGISTRATION_TOKEN=pending-token"));
        assertTrue(setCookieHeaders.getFirst().contains("Max-Age=600"));
        assertTrue(setCookieHeaders.get(1).contains("ACCESS_TOKEN="));
        assertTrue(setCookieHeaders.get(1).contains("Max-Age=0"));
        verify(jwtService).createPendingRegistrationToken(AuthProvider.KAKAO, "kakao-123");
        verifyNoMoreInteractions(jwtService);
    }

    @Test
    void ONBOARDING_회원은_ACCESS_TOKEN으로_온보딩_목적지에_진입한다() {
        // given
        given(jwtService.createServiceAuthToken(2L)).willReturn("service-token");
        SocialLoginResult loginResult = new SocialLoginResult.Authenticated(2L,
            LoginDestination.ONBOARDING);

        // when
        HttpSession session = org.mockito.Mockito.mock(HttpSession.class);
        given(oauthLoginCodeService.issue("service-token", session)).willReturn("onboarding-code");
        ResponseEntity<Void> response = authLoginResponseFactory.create(loginResult, session);

        // then
        assertEquals(HttpStatus.FOUND, response.getStatusCode());
        assertEquals(
            ONBOARDING_REDIRECT_URI + "#auth_code=onboarding-code",
            response.getHeaders().getLocation().toString());
        assertTrue(response.getHeaders().get(HttpHeaders.SET_COOKIE).getFirst()
            .contains("ACCESS_TOKEN="));
        verify(oauthLoginCodeService).issue("service-token", session);
        verify(jwtService).createServiceAuthToken(2L);
        verifyNoMoreInteractions(jwtService);
    }
}
