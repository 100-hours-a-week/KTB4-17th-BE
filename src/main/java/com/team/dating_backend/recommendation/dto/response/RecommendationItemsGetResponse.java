package com.team.dating_backend.recommendation.dto.response;

import java.util.List;

public record RecommendationItemsGetResponse(
    List<RecommendationItemResponse> items,
    RecommendationItemPageInfo pageInfo) {}
