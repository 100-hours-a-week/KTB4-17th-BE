package com.team.dating_backend.profiletrust.controller;

import com.team.dating_backend.common.dto.response.SuccessResponse;
import com.team.dating_backend.profiletrust.dto.request.CreateFaceVerificationRequest;
import com.team.dating_backend.profiletrust.dto.response.FaceVerificationResponse;
import com.team.dating_backend.profiletrust.service.FaceVerificationService;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/users/me/face-verifications")
public class FaceVerificationController {

    private final FaceVerificationService service;

    @PostMapping
    public SuccessResponse<FaceVerificationResponse> create(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal,
        @Valid @RequestBody CreateFaceVerificationRequest request) {
        return SuccessResponse.of("face_verification_session_success",
            service.create(principal.userId(), request.uploadIntentId()));
    }

    @GetMapping("/{sessionId}")
    public SuccessResponse<FaceVerificationResponse> get(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal,
        @PathVariable Long sessionId) {
        return SuccessResponse.of("face_verification_status_success", service.get(principal.userId(), sessionId));
    }

    @PostMapping(path = "/{sessionId}/attempts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SuccessResponse<FaceVerificationResponse> submit(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal,
        @PathVariable Long sessionId,
        @RequestParam int attemptNo,
        @RequestPart("video") MultipartFile video) {
        return SuccessResponse.of("face_verification_attempt_success",
            service.submit(principal.userId(), sessionId, attemptNo, video));
    }
}
