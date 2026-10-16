package com.team.dating_backend.recommendation.repository;

import com.team.dating_backend.profile.enums.JobCategory;
import com.team.dating_backend.profile.enums.Mbti;
import java.time.LocalDate;

public record RecommendationItemCandidateRow(
    Long itemId,
    Long userId,
    LocalDate birthDate,
    String nickname,
    JobCategory job,
    Mbti mbti,
    String provinceName,
    String regionName) {}
