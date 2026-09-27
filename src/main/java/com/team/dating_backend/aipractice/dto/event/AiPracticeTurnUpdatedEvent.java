package com.team.dating_backend.aipractice.dto.event;

import com.team.dating_backend.aipractice.enums.AiPracticeChatStatus;
import java.time.LocalDateTime;

public record AiPracticeTurnUpdatedEvent(
    Long userId,
    Long sessionId,
    Long chatId,
    AiPracticeChatStatus status,
    String aiResponse,
    String failureCode,
    LocalDateTime completedAt) {}
