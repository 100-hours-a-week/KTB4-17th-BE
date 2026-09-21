package com.team.dating_backend.user.controller;

import com.team.dating_backend.auth.controller.AuthCookieFactory;
import com.team.dating_backend.user.dto.request.OnboardingIdentityRequest;
import com.team.dating_backend.user.service.OnboardingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
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

    private static final String PENDING_ONBOARDING_TOKEN_COOKIE =
            AuthCookieFactory.PENDING_ONBOARDING_TOKEN_COOKIE;

    private final OnboardingService onboardingService;
    private final AuthCookieFactory authCookieFactory;

    @PutMapping("/identity")
    public ResponseEntity<Void> saveIdentity(
            @CookieValue(value = PENDING_ONBOARDING_TOKEN_COOKIE, required = false)
                    String pendingOnboardingToken,
            @Valid @RequestBody OnboardingIdentityRequest request) {

        String serviceToken = onboardingService.saveIdentity(pendingOnboardingToken, request);

        return ResponseEntity.ok()
                .header(
                        HttpHeaders.SET_COOKIE,
                        authCookieFactory.accessToken(serviceToken).toString())
                .header(
                        HttpHeaders.SET_COOKIE,
                        authCookieFactory.deletePendingOnboardingToken().toString())
                .build();
    }
}
