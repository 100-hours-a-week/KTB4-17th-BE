package com.team.dating_backend.recommendation.repository;

import com.team.dating_backend.profile.enums.Mbti;
import java.time.LocalDate;

public record RecommendationItemCandidateRow(
    Long itemId,
    Long memberId,
    LocalDate birthDate,
    String nickname,
    String job,
    Mbti mbti,
    String provinceName,
    String regionName) {}
