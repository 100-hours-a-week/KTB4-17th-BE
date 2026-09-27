package com.team.dating_backend.aipractice.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record AiPracticeSessionCreateRequest(@NotNull @Positive Long targetMemberId) {}
