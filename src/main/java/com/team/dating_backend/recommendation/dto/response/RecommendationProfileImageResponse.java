package com.team.dating_backend.recommendation.dto.response;

import java.time.Instant;

public record RecommendationProfileImageResponse(
    Long fileId,
    short displayOrder,
    String imageUrl,
    Instant expiresAt) {}
