package com.team.dating_backend.chat.dto.response;

import com.team.dating_backend.chat.enums.ChatMessageStatus;
import com.team.dating_backend.chat.enums.ChatMessageType;
import com.team.dating_backend.chat.enums.ChatRoomStatus;
import java.time.LocalDateTime;
import java.util.List;

public record ChatMessageListResponse(
    ChatRoomInfo chatRoom,
    List<Message> messages,
    PageInfo pageInfo) {

    public record ChatRoomInfo(
        Long chatRoomId,
        ChatRoomStatus status,
        boolean chatNotification,
        OtherParticipant otherParticipant) {}

    public record OtherParticipant(Long memberId, String nickname, String profileImageUrl) {}

    public record Message(
        Long messageId,
        boolean mine,
        ChatMessageType messageType,
        String textContent,
        ChatMessageStatus status,
        LocalDateTime createdAt) {}

    public record PageInfo(Long nextCursor, boolean hasNext) {}
}
