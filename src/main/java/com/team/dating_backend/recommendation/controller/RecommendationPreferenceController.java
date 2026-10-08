package com.team.dating_backend.recommendation.controller;

import com.team.dating_backend.common.dto.response.SuccessResponse;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import com.team.dating_backend.recommendation.dto.request.RecommendationPreferenceSaveRequest;
import com.team.dating_backend.recommendation.dto.response.RecommendationPreferenceGetResponse;
import com.team.dating_backend.recommendation.service.RecommendationPreferenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/users/me/preferences")
public class RecommendationPreferenceController {

    private final RecommendationPreferenceService recommendationPreferenceService;

    @GetMapping
    public ResponseEntity<SuccessResponse<RecommendationPreferenceGetResponse>> getPreferences(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal) {
        RecommendationPreferenceGetResponse response = recommendationPreferenceService
            .getPreferences(principal.userId());
        return ResponseEntity.ok(SuccessResponse.of("preference_get_success", response));
    }

    @PutMapping
    public ResponseEntity<Void> savePreferences(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal,
        @RequestBody RecommendationPreferenceSaveRequest request) {
        recommendationPreferenceService.savePreferences(principal.userId(), request);
        return ResponseEntity.noContent().build();
    }
}
