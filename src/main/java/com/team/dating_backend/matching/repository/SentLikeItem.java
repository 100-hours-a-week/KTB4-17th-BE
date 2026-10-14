package com.team.dating_backend.matching.repository;

import com.team.dating_backend.matching.enums.LikeStatus;
import com.team.dating_backend.profile.enums.JobCategory;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record SentLikeItem(
    Long likeId,
    Long userId,
    LocalDate birthDate,
    String nickname,
    JobCategory job,
    String provinceName,
    String regionName,
    LikeStatus status,
    LocalDateTime createdAt) {}
