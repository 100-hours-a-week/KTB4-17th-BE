package com.team.dating_backend.aipractice.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record AiPracticeChatCreateRequest(
    @NotNull UUID clientMessageId,
    @NotBlank @Size(max = 500) String userMessage) {}
