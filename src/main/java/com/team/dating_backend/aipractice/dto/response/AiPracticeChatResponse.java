package com.team.dating_backend.aipractice.dto.response;

import com.team.dating_backend.aipractice.enums.AiPracticeChatStatus;
import java.time.LocalDateTime;
import java.util.UUID;

public record AiPracticeChatResponse(
    Long id,
    UUID clientMessageId,
    String userMessage,
    String aiResponse,
    AiPracticeChatStatus status,
    boolean isRetry,
    String failureCode,
    LocalDateTime createdAt,
    LocalDateTime completedAt) {}
