package com.team.dating_backend.chat.dto.event;

public record ChatMessageReadReceiptEvent(
    Long chatRoomId,
    Long readerUserId,
    Long lastReadMessageId) {}
