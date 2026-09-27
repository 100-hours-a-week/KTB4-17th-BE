package com.team.dating_backend.aipractice.dto.response;

import com.team.dating_backend.aipractice.enums.AiPracticeChatStatus;
import java.time.LocalDateTime;

public record AiPracticeChatAcceptedResponse(
    Long sessionId,
    Long chatId,
    AiPracticeChatStatus status,
    LocalDateTime createdAt) {}
