package com.team.dating_backend.chat.dto.read;

import java.util.List;

public record ChatRoomPage(
    List<ChatRoomSummary> items,
    long totalUnreadCount,
    ChatRoomPageInfo pageInfo) {}
