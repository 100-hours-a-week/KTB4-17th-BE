package com.team.dating_backend.aipractice.dto.request;

import com.team.dating_backend.aipractice.enums.AiPracticeCallbackStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record AiPracticeGenerationCallbackRequest(
    @NotNull @Positive Long practiceSessionId,
    @NotNull @Positive Long chatId,
    @Size(max = 100) String aiSessionId,
    @NotNull @Positive Integer attempt,
    @NotNull AiPracticeCallbackStatus status,
    @Size(max = 10000) String aiResponse) {}
