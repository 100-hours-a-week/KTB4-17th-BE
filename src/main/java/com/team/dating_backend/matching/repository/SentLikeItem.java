package com.team.dating_backend.matching.repository;

import com.team.dating_backend.matching.enums.LikeStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record SentLikeItem(
    Long likeId,
    Long memberId,
    LocalDate birthDate,
    String nickname,
    String job,
    String provinceName,
    String regionName,
    LikeStatus status,
    LocalDateTime createdAt) {}
