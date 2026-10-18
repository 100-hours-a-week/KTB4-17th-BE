package com.team.dating_backend.auth.controller;

import com.team.dating_backend.auth.dto.AuthErrorResponse;
import com.team.dating_backend.auth.dto.IssuedAuthTokens;
import com.team.dating_backend.auth.dto.response.AuthTokenResponse;
import com.team.dating_backend.auth.service.AuthTokenService;
import com.team.dating_backend.common.enums.CommonErrorCode;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
public class AuthSessionController {

    private final AuthCookieFactory authCookieFactory;
    private final AuthTokenService authTokenService;

    @PostMapping("/token/refresh")
    public ResponseEntity<?> refresh(
        @CookieValue(
            value = AuthCookieFactory.REFRESH_TOKEN_COOKIE,
            required = false
        ) String refreshToken) {
        try {
            IssuedAuthTokens tokens = authTokenService.rotate(refreshToken);
            HttpHeaders headers = noStoreHeaders();
            headers.add(
                HttpHeaders.SET_COOKIE,
                authCookieFactory.refreshToken(
                    tokens.refreshToken(), tokens.refreshExpiresAt()).toString());

            return new ResponseEntity<>(
                AuthTokenResponse.bearer(tokens.accessToken()), headers, HttpStatus.OK);
        } catch (JwtException exception) {
            return authenticationRequired();
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
        @CookieValue(
            value = AuthCookieFactory.REFRESH_TOKEN_COOKIE,
            required = false
        ) String refreshToken) {
        authTokenService.revokeCurrent(refreshToken);
        HttpHeaders headers = noStoreHeaders();
        headers.add(HttpHeaders.SET_COOKIE, authCookieFactory.deleteAccessToken().toString());
        headers.add(HttpHeaders.SET_COOKIE, authCookieFactory.deleteRefreshToken().toString());
        headers.add(
            HttpHeaders.SET_COOKIE,
            authCookieFactory.deletePendingRegistrationToken().toString());
        return new ResponseEntity<>(headers, HttpStatus.NO_CONTENT);
    }

    private ResponseEntity<AuthErrorResponse> authenticationRequired() {
        HttpHeaders headers = noStoreHeaders();
        headers.add(HttpHeaders.SET_COOKIE, authCookieFactory.deleteAccessToken().toString());
        headers.add(HttpHeaders.SET_COOKIE, authCookieFactory.deleteRefreshToken().toString());
        return new ResponseEntity<>(
            new AuthErrorResponse(CommonErrorCode.AUTH_REQUIRED.name()),
            headers,
            CommonErrorCode.AUTH_REQUIRED.status());
    }

    private HttpHeaders noStoreHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("no-store");
        headers.setPragma("no-cache");
        return headers;
    }
}
