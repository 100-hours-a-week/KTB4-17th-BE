package com.team.dating_backend.recommendation.dto.response;

import com.team.dating_backend.profile.enums.JobCategory;
import com.team.dating_backend.profile.enums.Mbti;
import java.util.List;

public record RecommendationCandidateResponse(
    Long userId,
    String nickname,
    Integer age,
    JobCategory job,
    String region,
    Mbti mbti,
    List<RecommendationProfileImageResponse> images) {}
