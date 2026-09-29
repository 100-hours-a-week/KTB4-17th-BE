package com.team.dating_backend.profile.controller;

import com.team.dating_backend.common.dto.response.SuccessResponse;
import com.team.dating_backend.profile.dto.response.MyProfileResponse;
import com.team.dating_backend.profile.service.MyProfileGetService;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/users/me/profile")
public class MyProfileController {

    private final MyProfileGetService myProfileGetService;

    @GetMapping
    public ResponseEntity<SuccessResponse<MyProfileResponse>> getMyProfile(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal) {
        Optional<MyProfileResponse> profile = myProfileGetService.getMyProfile(principal.userId());
        if (profile.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(SuccessResponse.of("my_profile_get_success", profile.get()));
    }
}
