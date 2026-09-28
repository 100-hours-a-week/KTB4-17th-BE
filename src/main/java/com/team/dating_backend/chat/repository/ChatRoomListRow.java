package com.team.dating_backend.chat.repository;

import com.team.dating_backend.chat.enums.ChatRoomStatus;
import com.team.dating_backend.chat.enums.ChatMessageType;
import java.time.LocalDateTime;

public record ChatRoomListRow(
    Long chatRoomId,
    Long viewerParticipantId,
    boolean chatNotification,
    Long otherUserId,
    ChatRoomStatus roomStatus,
    Long lastMessageId,
    Long lastMessageSenderParticipantId,
    ChatMessageType lastMessageType,
    String lastMessageTextContent,
    Long lastMessageImageFileId,
    LocalDateTime lastMessageSenderDeletedAt,
    LocalDateTime lastMessageReceiverDeletedAt,
    Long unreadCount,
    LocalDateTime activityAt) {}
