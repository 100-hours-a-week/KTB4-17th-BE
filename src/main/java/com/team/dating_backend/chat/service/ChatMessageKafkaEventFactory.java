package com.team.dating_backend.chat.service;

import com.team.dating_backend.chat.delegation.repository.ChatDelegationMessageRepository;
import com.team.dating_backend.chat.dto.event.ChatMessageCreatedKafkaEvent;
import com.team.dating_backend.chat.entity.ChatMessage;
import com.team.dating_backend.chat.entity.ChatParticipant;
import com.team.dating_backend.chat.enums.ChatOutboxFailureCode;
import com.team.dating_backend.chat.enums.ChatParticipantStatus;
import com.team.dating_backend.chat.exception.ChatMessageKafkaEventCreationException;
import com.team.dating_backend.chat.repository.ChatMessageRepository;
import com.team.dating_backend.chat.repository.ChatParticipantRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class ChatMessageKafkaEventFactory {

    private static final String EVENT_ID_PREFIX = "chat-message-created:";

    private final ChatMessageRepository chatMessageRepository;
    private final ChatParticipantRepository chatParticipantRepository;
    private final ChatDelegationMessageRepository delegationMessageRepository;

    @Transactional(readOnly = true)
    public ChatMessageCreatedKafkaEvent create(Long chatMessageId) {
        ChatMessage message = chatMessageRepository.findByIdWithImageFile(chatMessageId)
            .orElseThrow(() -> failure(ChatOutboxFailureCode.MESSAGE_NOT_FOUND));
        List<ChatParticipant> participants = chatParticipantRepository
            .findAllByChatRoomId(message.getChatRoomId());
        ChatParticipant sender = findSender(message, participants);
        ChatParticipant receiver = findReceiver(sender, participants);
        Long hiddenParticipantId = delegationMessageRepository
            .findRequesterParticipantIdByChatMessageId(message.getId())
            .orElse(null);
        List<Long> realtimeTargetUserIds = participants.stream()
            .filter(participant -> participant.getStatus() == ChatParticipantStatus.ACTIVE)
            .filter(participant -> !participant.getId().equals(hiddenParticipantId))
            .map(ChatParticipant::getUserId)
            .sorted()
            .toList();
        boolean isUnreadByReceiver = receiver.getLastReadMessageId() == null
            || message.getId() > receiver.getLastReadMessageId();

        return new ChatMessageCreatedKafkaEvent(
            EVENT_ID_PREFIX + message.getId(),
            message.getCreatedAt(),
            message.getChatRoomId(),
            message.getId(),
            message.getClientMessageId(),
            sender.getUserId(),
            receiver.getUserId(),
            realtimeTargetUserIds,
            hiddenParticipantId != null,
            message.getMessageType(),
            message.getTextContent(),
            message.getImageFileId(),
            isUnreadByReceiver,
            message.getCreatedAt());
    }

    private ChatParticipant findSender(
        ChatMessage message, List<ChatParticipant> participants) {
        if (participants.size() != 2) {
            throw failure(ChatOutboxFailureCode.PARTICIPANTS_INVALID);
        }
        return participants.stream()
            .filter(participant -> participant.getId().equals(message.getSenderParticipantId()))
            .findFirst()
            .orElseThrow(() -> failure(ChatOutboxFailureCode.PARTICIPANTS_INVALID));
    }

    private ChatParticipant findReceiver(
        ChatParticipant sender, List<ChatParticipant> participants) {
        return participants.stream()
            .filter(participant -> !participant.getId().equals(sender.getId()))
            .findFirst()
            .orElseThrow(() -> failure(ChatOutboxFailureCode.PARTICIPANTS_INVALID));
    }

    private ChatMessageKafkaEventCreationException failure(
        ChatOutboxFailureCode failureCode) {
        return new ChatMessageKafkaEventCreationException(failureCode);
    }
}
