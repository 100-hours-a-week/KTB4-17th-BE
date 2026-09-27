package com.team.dating_backend.recommendation.dto.response;

public record RecommendationProfileImageResponse(
    Long fileId,
    short displayOrder,
    String imageUrl) {}
