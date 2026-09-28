package com.team.dating_backend.user.controller;

import com.team.dating_backend.auth.controller.AuthCookieFactory;
import com.team.dating_backend.auth.dto.response.AuthTokenResponse;
import com.team.dating_backend.user.dto.request.UserRegistrationRequest;
import com.team.dating_backend.user.service.UserRegistrationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/registration")
public class UserRegistrationController {

    private static final String PENDING_REGISTRATION_TOKEN_COOKIE = AuthCookieFactory.PENDING_REGISTRATION_TOKEN_COOKIE;

    private final UserRegistrationService userRegistrationService;
    private final AuthCookieFactory authCookieFactory;

    @PutMapping("/identity")
    public ResponseEntity<AuthTokenResponse> registerUser(
        @CookieValue(
            value = PENDING_REGISTRATION_TOKEN_COOKIE,
            required = false
        ) String pendingRegistrationToken,
        @Valid @RequestBody UserRegistrationRequest request) {

        String serviceToken = userRegistrationService.registerUser(pendingRegistrationToken,
            request);

        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .header(
                HttpHeaders.SET_COOKIE,
                authCookieFactory.deleteAccessToken().toString())
            .header(
                HttpHeaders.SET_COOKIE,
                authCookieFactory.deletePendingRegistrationToken().toString())
            .body(AuthTokenResponse.bearer(serviceToken));
    }
}
