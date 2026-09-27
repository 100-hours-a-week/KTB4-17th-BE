package com.team.dating_backend.aipractice.dto.response;

import com.team.dating_backend.aipractice.enums.AiPracticeSessionStatus;
import java.time.LocalDateTime;

public record AiPracticeSessionResponse(
    Long id,
    Long targetMemberId,
    AiPracticeSessionStatus status,
    LocalDateTime startedAt,
    LocalDateTime endedAt,
    boolean resumed) {}
