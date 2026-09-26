package com.team.dating_backend.chat.repository;

import com.team.dating_backend.chat.enums.ChatRoomStatus;
import java.time.LocalDateTime;

public record ChatRoomListRow(
    Long chatRoomId,
    Long viewerParticipantId,
    boolean chatNotification,
    Long otherUserId,
    ChatRoomStatus roomStatus,
    Long lastMessageId,
    Long lastMessageSenderParticipantId,
    String lastMessageTextContent,
    LocalDateTime lastMessageSenderDeletedAt,
    LocalDateTime lastMessageReceiverDeletedAt,
    LocalDateTime activityAt) {}
