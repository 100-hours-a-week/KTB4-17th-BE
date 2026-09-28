package com.team.dating_backend.recommendation.controller;

import com.team.dating_backend.common.dto.response.SuccessResponse;
import com.team.dating_backend.recommendation.dto.response.RecommendationBatchCreateResponse;
import com.team.dating_backend.recommendation.dto.response.RecommendationBatchGetResponse;
import com.team.dating_backend.recommendation.dto.response.RecommendationItemsGetResponse;
import com.team.dating_backend.recommendation.service.RecommendationBatchCreateService;
import com.team.dating_backend.recommendation.service.RecommendationBatchGetService;
import com.team.dating_backend.recommendation.service.RecommendationItemGetService;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/recommendation-batches")
public class RecommendationBatchController {

    private final RecommendationBatchCreateService recommendationBatchCreateService;
    private final RecommendationBatchGetService recommendationBatchGetService;
    private final RecommendationItemGetService recommendationItemGetService;

    @PostMapping
    public ResponseEntity<SuccessResponse<RecommendationBatchCreateResponse>> createRecommendationBatch(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal) {
        Optional<RecommendationBatchCreateResponse> response = recommendationBatchCreateService
            .createRecommendationBatch(principal.userId());
        if (response.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(SuccessResponse.of("recommendation_batch_create_success", response.get()));
    }

    @GetMapping("/active")
    public ResponseEntity<SuccessResponse<RecommendationBatchGetResponse>> getActiveRecommendationBatch(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal) {
        RecommendationBatchGetResponse response = recommendationBatchGetService
            .getActiveRecommendationBatch(principal.userId());
        return ResponseEntity.ok(SuccessResponse.of("recommendation_batch_get_success", response));
    }

    @GetMapping("/{batchId}/items")
    public ResponseEntity<SuccessResponse<RecommendationItemsGetResponse>> getRecommendationItems(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal,
        @PathVariable Long batchId,
        @RequestParam(required = false) Long cursor) {
        RecommendationItemsGetResponse response = recommendationItemGetService
            .getRecommendationItems(principal.userId(), batchId, cursor);
        return ResponseEntity.ok(SuccessResponse.of("recommendation_items_get_success", response));
    }
}
