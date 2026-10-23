package com.team.dating_backend.chat.dto.read;

import com.team.dating_backend.chat.delegation.dto.response.ChatDelegationSummary;
import java.time.LocalDateTime;

public record ChatRoomSummary(
    Long chatRoomId,
    Long otherUserId,
    boolean chatNotification,
    ChatRoomPreview preview,
    ChatDelegationSummary delegation,
    long unreadCount,
    LocalDateTime activityAt) {}
