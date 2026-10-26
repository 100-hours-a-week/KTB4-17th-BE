package com.team.dating_backend.chat.dto.event;

import com.team.dating_backend.chat.enums.ChatMessageType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record ChatMessageCreatedKafkaEvent(
    String eventId,
    LocalDateTime occurredAt,
    Long chatRoomId,
    Long messageId,
    UUID clientMessageId,
    Long senderUserId,
    Long receiverUserId,
    List<Long> realtimeTargetUserIds,
    boolean isDelegated,
    ChatMessageType messageType,
    String textContent,
    Long imageFileId,
    boolean isUnreadByReceiver,
    LocalDateTime createdAt) {

    public ChatMessageCreatedKafkaEvent {
        realtimeTargetUserIds = List.copyOf(realtimeTargetUserIds);
    }
}
