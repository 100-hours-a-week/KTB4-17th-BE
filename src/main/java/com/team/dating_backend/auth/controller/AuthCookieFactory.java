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
    public static final String REFRESH_TOKEN_COOKIE = "REFRESH_TOKEN";
    public static final String PENDING_REGISTRATION_TOKEN_COOKIE = "PENDING_REGISTRATION_TOKEN";

    private final AuthWebProperties authWebProperties;
    private final JwtProperties jwtProperties;

    public ResponseCookie refreshToken(String token) {
        return create(
            REFRESH_TOKEN_COOKIE,
            token,
            Duration.ofDays(jwtProperties.getRefreshExpirationDays()),
            "/api/v1/auth");
    }
    public ResponseCookie pendingRegistrationToken(String token) {
        return create(
            PENDING_REGISTRATION_TOKEN_COOKIE,
            token,
            Duration.ofMinutes(jwtProperties.getPendingExpirationMinutes()));
    }

    public ResponseCookie deleteAccessToken() {
        return create(ACCESS_TOKEN_COOKIE, "", Duration.ZERO);
    }

    public ResponseCookie deleteRefreshToken() {
        return create(REFRESH_TOKEN_COOKIE, "", Duration.ZERO, "/api/v1/auth");
    }

    public ResponseCookie deletePendingRegistrationToken() {
        return create(PENDING_REGISTRATION_TOKEN_COOKIE, "", Duration.ZERO);
    }

    private ResponseCookie create(String name, String value, Duration maxAge) {
        return create(name, value, maxAge, "/");
    }

    private ResponseCookie create(String name, String value, Duration maxAge, String path) {
        return ResponseCookie.from(name, value)
            .httpOnly(true)
            .secure(authWebProperties.isSecureCookie())
            .sameSite(authWebProperties.getSameSite())
            .path(path)
            .maxAge(maxAge)
            .build();
    }
}
