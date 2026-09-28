package com.team.dating_backend.chat.dto.event;

public record ChatMessageReadRequestedEvent(
    Long recipientUserId,
    ChatMessageReadReceiptEvent receipt) {}
