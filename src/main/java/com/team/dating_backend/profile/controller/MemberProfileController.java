package com.team.dating_backend.profile.controller;

import com.team.dating_backend.common.dto.response.SuccessResponse;
import com.team.dating_backend.profile.dto.response.MemberProfileResponse;
import com.team.dating_backend.profile.service.MemberProfileGetService;
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
@RequestMapping("/api/v1/users/{memberId}/profile")
public class MemberProfileController {

    private final MemberProfileGetService memberProfileGetService;

    @GetMapping
    public ResponseEntity<SuccessResponse<MemberProfileResponse>> getMemberProfile(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal,
        @PathVariable Long memberId) {
        MemberProfileResponse profile = memberProfileGetService.getMemberProfile(
            principal.userId(), memberId);
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(SuccessResponse.of("member_profile_get_success", profile));
    }
}
