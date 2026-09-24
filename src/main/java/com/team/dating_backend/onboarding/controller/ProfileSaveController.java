package com.team.dating_backend.onboarding.controller;

import com.team.dating_backend.common.dto.response.SuccessResponse;
import com.team.dating_backend.onboarding.dto.request.ProfileSaveRequest;
import com.team.dating_backend.onboarding.dto.response.ProfileSaveResponse;
import com.team.dating_backend.onboarding.dto.response.ProfileSaveResult;
import com.team.dating_backend.onboarding.service.ProfileSaveService;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/users/me/profile")
public class ProfileSaveController {

    private final ProfileSaveService profileSaveService;

    @PutMapping
    public ResponseEntity<SuccessResponse<ProfileSaveResponse>> saveProfile(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal,
        @Valid @RequestBody ProfileSaveRequest request) {
        ProfileSaveResult result = profileSaveService.saveProfile(principal.userId(), request);

        if (!result.created()) {
            return ResponseEntity.noContent().build();
        }

        return ResponseEntity.status(HttpStatus.CREATED)
            .body(SuccessResponse.of("profile_save_success", result.response()));
    }
}
