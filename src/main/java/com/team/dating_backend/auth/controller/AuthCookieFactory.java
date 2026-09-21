package com.team.dating_backend.auth.controller;

import com.team.dating_backend.auth.config.AuthWebProperties;
import com.team.dating_backend.auth.config.JwtProperties;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AuthCookieFactory {

    public static final String ACCESS_TOKEN_COOKIE = "ACCESS_TOKEN";
    public static final String PENDING_ONBOARDING_TOKEN_COOKIE = "PENDING_ONBOARDING_TOKEN";

    private final AuthWebProperties authWebProperties;
    private final JwtProperties jwtProperties;

    public ResponseCookie accessToken(String token) {
        return create(
                ACCESS_TOKEN_COOKIE,
                token,
                Duration.ofMinutes(jwtProperties.getServiceExpirationMinutes()));
    }

    public ResponseCookie pendingOnboardingToken(String token) {
        return create(
                PENDING_ONBOARDING_TOKEN_COOKIE,
                token,
                Duration.ofMinutes(jwtProperties.getPendingExpirationMinutes()));
    }

    public ResponseCookie deleteAccessToken() {
        return create(ACCESS_TOKEN_COOKIE, "", Duration.ZERO);
    }

    public ResponseCookie deletePendingOnboardingToken() {
        return create(PENDING_ONBOARDING_TOKEN_COOKIE, "", Duration.ZERO);
    }

    private ResponseCookie create(String name, String value, Duration maxAge) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(authWebProperties.isSecureCookie())
                .sameSite(authWebProperties.getSameSite())
                .path("/")
                .maxAge(maxAge)
                .build();
    }
}
