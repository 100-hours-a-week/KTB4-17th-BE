package com.team.dating_backend.recommendation.dto.response;

import com.team.dating_backend.profile.enums.Mbti;
import java.util.List;

public record RecommendationCandidateResponse(
    Long memberId,
    String nickname,
    Integer age,
    String job,
    String region,
    Mbti mbti,
    List<RecommendationProfileImageResponse> images) {}
