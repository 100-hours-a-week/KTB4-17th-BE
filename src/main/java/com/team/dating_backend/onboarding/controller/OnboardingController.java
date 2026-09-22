package com.team.dating_backend.onboarding.controller;

import com.team.dating_backend.common.dto.response.SuccessResponse;
import com.team.dating_backend.onboarding.dto.response.OnboardingProfileResponse;
import com.team.dating_backend.onboarding.dto.response.OnboardingStatusResponse;
import com.team.dating_backend.onboarding.service.OnboardingService;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/users/me/onboarding")
public class OnboardingController {

    private final OnboardingService onboardingService;

    @GetMapping
    public SuccessResponse<OnboardingStatusResponse> showOnboardingStatus(
            @AuthenticationPrincipal ServiceAuthenticationPrincipal principal) {
        return SuccessResponse.of(
                "onboarding_status_get_success",
                onboardingService.getOnboardingStatus(principal.userId()));
    }

    @GetMapping("/profile")
    public SuccessResponse<OnboardingProfileResponse> showOnboardingProfile(
            @AuthenticationPrincipal ServiceAuthenticationPrincipal principal) {
        return SuccessResponse.of(
                "user_onboarding_profile_get_success",
                onboardingService.getOnboardingProfile(principal.userId()));
    }
}
