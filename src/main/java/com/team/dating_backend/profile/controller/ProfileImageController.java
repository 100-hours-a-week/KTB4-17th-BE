package com.team.dating_backend.profile.controller;

import com.team.dating_backend.common.dto.response.SuccessResponse;
import com.team.dating_backend.profile.dto.request.ProfileImageSaveRequest;
import com.team.dating_backend.profile.dto.response.ProfileImageSaveResponse;
import com.team.dating_backend.profile.service.ProfileImageSaveService;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/users/me/profile/images")
public class ProfileImageController {

    private final ProfileImageSaveService profileImageSaveService;

    @PutMapping
    public ResponseEntity<SuccessResponse<ProfileImageSaveResponse>> saveProfileImages(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal,
        @Valid @RequestBody ProfileImageSaveRequest request) {
        return ResponseEntity.ok(SuccessResponse.of(
            "profile_image_save_success",
            profileImageSaveService.saveProfileImages(principal.userId(), request)));
    }
}
