package com.team.dating_backend.profile.controller;

import com.team.dating_backend.common.dto.response.SuccessResponse;
import com.team.dating_backend.profile.dto.response.UserProfileResponse;
import com.team.dating_backend.profile.service.UserProfileGetService;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/users/{userId}/profile")
public class UserProfileController {

    private final UserProfileGetService userProfileGetService;

    @GetMapping
    public ResponseEntity<SuccessResponse<UserProfileResponse>> getUserProfile(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal,
        @PathVariable Long userId) {
        UserProfileResponse profile = userProfileGetService.getUserProfile(
            principal.userId(), userId);
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(SuccessResponse.of("user_profile_get_success", profile));
    }
}
