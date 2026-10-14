package com.team.dating_backend.recommendation.dto.response;

import java.time.LocalDateTime;

public record RecommendationPassSaveResponse(
    Long targetUserId, LocalDateTime passedAt, boolean permanent) {}
