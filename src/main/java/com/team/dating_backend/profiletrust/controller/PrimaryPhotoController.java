package com.team.dating_backend.profiletrust.controller;

import com.team.dating_backend.common.dto.response.SuccessResponse;
import com.team.dating_backend.profiletrust.dto.response.PrimaryPhotoCheckResponse;
import com.team.dating_backend.profiletrust.service.PrimaryPhotoCheckService;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/files/upload-intents")
public class PrimaryPhotoController {

    private final PrimaryPhotoCheckService service;

    @PostMapping("/{uploadIntentId}/primary-photo-check")
    public SuccessResponse<PrimaryPhotoCheckResponse> check(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal,
        @PathVariable Long uploadIntentId) {
        return SuccessResponse.of("primary_photo_check_success",
            PrimaryPhotoCheckResponse.from(service.check(principal.userId(), uploadIntentId)));
    }
}
