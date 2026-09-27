package com.team.dating_backend.chat.dto.response;

import com.team.dating_backend.chat.enums.ChatRoomPreviewType;
import java.time.LocalDateTime;
import java.util.List;

public record ChatRoomListResponse(
    List<Item> items,
    long totalUnreadCount,
    PageInfo pageInfo) {

    public record Item(
        Long chatRoomId,
        boolean chatNotification,
        OtherParticipant otherParticipant,
        Preview preview,
        long unreadCount,
        LocalDateTime activityAt) {}

    public record OtherParticipant(String nickname, String profileImageUrl) {}

    public record Preview(ChatRoomPreviewType type, String text, Long imageFileId) {}

    public record PageInfo(String nextCursor, boolean hasNext) {}
}
