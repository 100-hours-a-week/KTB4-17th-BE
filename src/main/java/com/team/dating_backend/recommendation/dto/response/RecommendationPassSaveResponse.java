package com.team.dating_backend.recommendation.dto.response;

import java.time.LocalDateTime;

public record RecommendationPassSaveResponse(
    Long targetMemberId, LocalDateTime passedAt, boolean permanent) {}
