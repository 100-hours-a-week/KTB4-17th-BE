package com.team.dating_backend.auth.controller;

import com.team.dating_backend.auth.dto.AuthErrorResponse;
import com.team.dating_backend.auth.dto.response.AuthTokenResponse;
import com.team.dating_backend.auth.service.JwtService;
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
    private final JwtService jwtService;

    @PostMapping("/token/refresh")
    public ResponseEntity<?> refresh(
        @CookieValue(
            value = AuthCookieFactory.REFRESH_TOKEN_COOKIE,
            required = false
        ) String refreshToken) {
        try {
            Long userId = jwtService.parseRefreshAuthToken(refreshToken);
            String accessToken = jwtService.createServiceAuthToken(userId);

            return new ResponseEntity<>(
                AuthTokenResponse.bearer(accessToken), noStoreHeaders(), HttpStatus.OK);
        } catch (JwtException exception) {
            HttpHeaders headers = noStoreHeaders();
            headers.add(HttpHeaders.SET_COOKIE, authCookieFactory.deleteAccessToken().toString());
            headers.add(HttpHeaders.SET_COOKIE, authCookieFactory.deleteRefreshToken().toString());
            return new ResponseEntity<>(
                new AuthErrorResponse("AUTH_REQUIRED"), headers, HttpStatus.UNAUTHORIZED);
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        HttpHeaders headers = noStoreHeaders();
        headers.add(HttpHeaders.SET_COOKIE, authCookieFactory.deleteAccessToken().toString());
        headers.add(HttpHeaders.SET_COOKIE, authCookieFactory.deleteRefreshToken().toString());
        headers.add(
            HttpHeaders.SET_COOKIE,
            authCookieFactory.deletePendingRegistrationToken().toString());
        return new ResponseEntity<>(headers, HttpStatus.NO_CONTENT);
    }

    private HttpHeaders noStoreHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("no-store");
        headers.setPragma("no-cache");
        return headers;
    }
}
