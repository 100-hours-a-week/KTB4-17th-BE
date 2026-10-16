package com.team.dating_backend.recommendation.service;

import com.team.dating_backend.recommendation.dto.response.RecommendationPassSaveResponse;

public record RecommendationPassSaveResult(
    RecommendationPassSaveResponse response, boolean created) {}
