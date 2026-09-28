package com.team.dating_backend.aipractice.dto.response;

import java.time.LocalDate;

public record AiPracticeUsageResponse(
    LocalDate usageDate,
    long used,
    long reserved,
    int dailyLimit,
    int remaining) {}
