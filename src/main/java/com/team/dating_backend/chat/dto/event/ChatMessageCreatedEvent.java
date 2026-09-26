package com.team.dating_backend.chat.dto.event;

import com.team.dating_backend.chat.enums.ChatMessageType;
import java.time.LocalDateTime;
import java.util.UUID;

public record ChatMessageCreatedEvent(
    Long chatRoomId,
    Long messageId,
    UUID clientMessageId,
    boolean mine,
    ChatMessageType messageType,
    String textContent,
    LocalDateTime createdAt) {}
