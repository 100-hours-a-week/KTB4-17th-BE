package com.team.dating_backend.chat.service;

import com.team.dating_backend.chat.dto.request.ChatMessageCreateRequest;
import com.team.dating_backend.chat.dto.response.ChatMessageCreateResponse;
import com.team.dating_backend.chat.entity.ChatMessage;
import com.team.dating_backend.chat.entity.ChatMessageOutbox;
import com.team.dating_backend.chat.entity.ChatParticipant;
import com.team.dating_backend.chat.entity.ChatRoom;
import com.team.dating_backend.chat.enums.ChatErrorCode;
import com.team.dating_backend.chat.enums.ChatMessageType;
import com.team.dating_backend.chat.enums.ChatParticipantStatus;
import com.team.dating_backend.chat.enums.ChatRoomStatus;
import com.team.dating_backend.chat.exception.ChatBusinessException;
import com.team.dating_backend.chat.repository.ChatMessageOutboxRepository;
import com.team.dating_backend.chat.repository.ChatMessageRepository;
import com.team.dating_backend.chat.repository.ChatParticipantRepository;
import com.team.dating_backend.chat.repository.ChatRoomRepository;
import com.team.dating_backend.common.exception.RequestValidationException;
import com.team.dating_backend.user.enums.UserStatus;
import com.team.dating_backend.user.repository.UserBlockRepository;
import com.team.dating_backend.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatMessageSendService {

    private static final int MAX_TEXT_LENGTH = 1000;

    private final ChatRoomRepository chatRoomRepository;
    private final ChatParticipantRepository chatParticipantRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatMessageOutboxRepository outboxRepository;
    private final ChatMessageRateLimiter rateLimiter;
    private final UserRepository userRepository;
    private final UserBlockRepository userBlockRepository;

    @Transactional
    public ChatMessageCreateResponse sendTextMessage(
        Long chatRoomId, Long senderUserId, ChatMessageCreateRequest request) {
        if (request == null || request.clientMessageId() == null) {
            throw new RequestValidationException();
        }

        ChatRoom room = chatRoomRepository.findById(chatRoomId)
            .orElseThrow(() -> new ChatBusinessException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));
        List<ChatParticipant> participants = chatParticipantRepository.findAllByChatRoomId(chatRoomId);
        ChatParticipant sender = participants.stream()
            .filter(participant -> participant.getUserId().equals(senderUserId))
            .findFirst()
            .orElseThrow(() -> new ChatBusinessException(ChatErrorCode.CHAT_ACCESS_DENIED));

        if (sender.getStatus() != ChatParticipantStatus.ACTIVE) {
            throw new ChatBusinessException(ChatErrorCode.CHAT_ACCESS_DENIED);
        }

        ChatMessage existing = chatMessageRepository.findBySenderParticipantIdAndClientMessageId(
            sender.getId(), request.clientMessageId()).orElse(null);
        if (existing != null) {
            if (sameRequest(existing, chatRoomId, request)) {
                return new ChatMessageCreateResponse(existing.getId(), existing.getCreatedAt());
            }
            throw new ChatBusinessException(ChatErrorCode.CLIENT_MESSAGE_ID_CONFLICT);
        }

        validateTextRequest(request);
        requireSendableRoom(room, participants, sender);
        if (!rateLimiter.tryAcquire(senderUserId)) {
            throw new ChatBusinessException(ChatErrorCode.TOO_MANY_MESSAGE_REQUESTS);
        }

        LocalDateTime now = LocalDateTime.now();
        ChatMessage message = chatMessageRepository.save(new ChatMessage(
            chatRoomId, sender.getId(), request.clientMessageId(), request.textContent(), now));
        outboxRepository.save(new ChatMessageOutbox(message.getId(), now));

        return new ChatMessageCreateResponse(message.getId(), message.getCreatedAt());
    }

    private boolean sameRequest(
        ChatMessage existing, Long chatRoomId, ChatMessageCreateRequest request) {
        return existing.getChatRoomId().equals(chatRoomId)
            && existing.getMessageType() == request.messageType()
            && Objects.equals(existing.getTextContent(), request.textContent());
    }

    private void validateTextRequest(ChatMessageCreateRequest request) {
        String text = request.textContent();
        if (request.messageType() != ChatMessageType.TEXT
            || text == null || text.isBlank() || text.length() > MAX_TEXT_LENGTH) {
            throw new RequestValidationException();
        }
    }

    private void requireSendableRoom(
        ChatRoom room, List<ChatParticipant> participants, ChatParticipant sender) {
        if (participants.size() != 2) {
            throw new ChatBusinessException(ChatErrorCode.INTERNAL_SERVER_ERROR);
        }

        ChatParticipant receiver = participants.stream()
            .filter(participant -> !participant.getId().equals(sender.getId()))
            .findFirst()
            .orElseThrow(() -> new ChatBusinessException(
                ChatErrorCode.INTERNAL_SERVER_ERROR));

        if (room.getStatus() != ChatRoomStatus.ACTIVE
            || receiver.getStatus() != ChatParticipantStatus.ACTIVE
            || !isActiveUser(sender.getUserId())
            || !isActiveUser(receiver.getUserId())
            || userBlockRepository.existsActiveBlockBetween(
                sender.getUserId(), receiver.getUserId())) {
            throw new ChatBusinessException(ChatErrorCode.CHAT_ROOM_NOT_ACTIVE);
        }
    }

    private boolean isActiveUser(Long userId) {
        return userRepository.findById(userId)
            .map(user -> user.getStatus() == UserStatus.ACTIVE)
            .orElse(false);
    }
}
