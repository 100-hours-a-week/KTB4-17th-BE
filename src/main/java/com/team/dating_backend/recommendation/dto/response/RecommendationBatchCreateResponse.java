package com.team.dating_backend.recommendation.dto.response;

import java.time.LocalDateTime;

public record RecommendationBatchCreateResponse(Long batchId, LocalDateTime createdAt) {}
