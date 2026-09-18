package com.team.dating_backend.user.controller;

import com.team.dating_backend.auth.config.JwtProperties;
import com.team.dating_backend.user.dto.request.OnboardingIdentityRequest;
import com.team.dating_backend.user.service.OnboardingService;
import jakarta.validation.Valid;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/onboarding")
public class OnboardingController {

    private static final String PENDING_ONBOARDING_TOKEN_COOKIE = "PENDING_ONBOARDING_TOKEN";
    private static final String ACCESS_TOKEN_COOKIE = "ACCESS_TOKEN";

    private final OnboardingService onboardingService;
    private final JwtProperties jwtProperties;

    @PutMapping("/identity")
    public ResponseEntity<Void> saveIdentity(
            @CookieValue(value = PENDING_ONBOARDING_TOKEN_COOKIE, required = false)
                    String pendingOnboardingToken,
            @Valid @RequestBody OnboardingIdentityRequest request) {

        String serviceToken = onboardingService.saveIdentity(pendingOnboardingToken, request);
        ResponseCookie accessCookie =
                ResponseCookie.from(ACCESS_TOKEN_COOKIE, serviceToken)
                        .httpOnly(true)
                        .secure(false)
                        .sameSite("Lax")
                        .path("/")
                        .maxAge(Duration.ofMinutes(jwtProperties.getServiceExpirationMinutes()))
                        .build();

        ResponseCookie deletePendingCookie =
                ResponseCookie.from(PENDING_ONBOARDING_TOKEN_COOKIE, "")
                        .httpOnly(true)
                        .secure(false)
                        .sameSite("Lax")
                        .path("/")
                        .maxAge(Duration.ZERO)
                        .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, accessCookie.toString())
                .header(HttpHeaders.SET_COOKIE, deletePendingCookie.toString())
                .build();
    }
}
