package com.team.dating_backend.chat.dto.read;

import java.time.LocalDateTime;

public record ChatRoomSummary(
    Long chatRoomId,
    Long otherUserId,
    boolean chatNotification,
    ChatRoomPreview preview,
    long unreadCount,
    LocalDateTime activityAt) {}
