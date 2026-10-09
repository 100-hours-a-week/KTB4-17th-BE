package com.team.dating_backend.recommendation.controller;

import com.team.dating_backend.common.dto.response.SuccessResponse;
import com.team.dating_backend.recommendation.dto.response.RecommendationPassSaveResponse;
import com.team.dating_backend.recommendation.service.RecommendationPassSaveResult;
import com.team.dating_backend.recommendation.service.RecommendationPassSaveService;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/members/me/recommendation-passes")
public class RecommendationPassController {

    private final RecommendationPassSaveService recommendationPassSaveService;

    @PutMapping("/{targetMemberId}")
    public ResponseEntity<SuccessResponse<RecommendationPassSaveResponse>> savePass(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal,
        @PathVariable Long targetMemberId) {
        RecommendationPassSaveResult result = recommendationPassSaveService
            .savePass(principal.userId(), targetMemberId);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        String message = result.created()
            ? "recommendation_pass_success"
            : "recommendation_pass_already_exists";
        return ResponseEntity.status(status).body(SuccessResponse.of(message, result.response()));
    }
}
