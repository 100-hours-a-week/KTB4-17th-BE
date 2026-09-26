package com.team.dating_backend.chat.service;

import com.team.dating_backend.chat.entity.ChatMessage;
import com.team.dating_backend.chat.entity.ChatParticipant;
import com.team.dating_backend.chat.entity.ChatRoom;
import com.team.dating_backend.chat.enums.ChatErrorCode;
import com.team.dating_backend.chat.enums.ChatMessageStatus;
import com.team.dating_backend.chat.enums.ChatMessageType;
import com.team.dating_backend.chat.enums.ChatParticipantStatus;
import com.team.dating_backend.chat.enums.ChatRoomStatus;
import com.team.dating_backend.chat.exception.ChatBusinessException;
import com.team.dating_backend.chat.repository.ChatMessageRepository;
import com.team.dating_backend.chat.repository.ChatParticipantRepository;
import com.team.dating_backend.chat.repository.ChatRoomRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatMessageListService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final String DELETED_CONTENT = "삭제된 메시지입니다.";

    private final ChatRoomRepository chatRoomRepository;
    private final ChatParticipantRepository chatParticipantRepository;
    private final ChatMessageRepository chatMessageRepository;

    @Transactional(readOnly = true)
    public MessagePage listMessages(Long chatRoomId, Long viewerUserId, Long cursor, int size) {
        validatePagination(cursor, size);

        ChatRoom room = chatRoomRepository.findById(chatRoomId)
            .orElseThrow(() -> new ChatBusinessException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));
        List<ChatParticipant> participants = chatParticipantRepository.findAllByChatRoomId(chatRoomId);
        ChatParticipant viewer = participants.stream()
            .filter(participant -> participant.getUserId().equals(viewerUserId))
            .findFirst()
            .orElseThrow(() -> new ChatBusinessException(ChatErrorCode.CHAT_ACCESS_DENIED));

        if (viewer.getStatus() != ChatParticipantStatus.ACTIVE
            || room.getStatus() == ChatRoomStatus.INACTIVE) {
            throw new ChatBusinessException(ChatErrorCode.CHAT_ACCESS_DENIED);
        }

        List<ChatParticipant> others = participants.stream()
            .filter(participant -> !participant.getId().equals(viewer.getId()))
            .toList();
        if (others.size() != 1) {
            throw new ChatBusinessException(ChatErrorCode.INTERNAL_SERVER_ERROR);
        }

        List<ChatMessage> fetched = cursor == null
            ? chatMessageRepository.findByChatRoomIdAndStatusAndMessageTypeOrderByIdDesc(
                chatRoomId, ChatMessageStatus.SENT, ChatMessageType.TEXT,
                PageRequest.of(0, size + 1))
            : chatMessageRepository.findByChatRoomIdAndStatusAndMessageTypeAndIdLessThanOrderByIdDesc(
                chatRoomId, ChatMessageStatus.SENT, ChatMessageType.TEXT, cursor,
                PageRequest.of(0, size + 1));

        boolean hasNext = fetched.size() > size;
        List<ChatMessage> page = new ArrayList<>(fetched.subList(0, Math.min(size, fetched.size())));
        Long nextCursor = hasNext ? page.getLast().getId() : null;
        Collections.reverse(page);

        List<MessageItem> messages = page.stream()
            .map(message -> toMessageItem(message, viewer.getId()))
            .toList();

        return new MessagePage(
            room.getId(), room.getStatus(), viewer.isChatNotification(),
            others.getFirst().getUserId(), messages, nextCursor, hasNext);
    }

    private void validatePagination(Long cursor, int size) {
        if (cursor != null && cursor <= 0) {
            throw new ChatBusinessException(ChatErrorCode.INVALID_CHAT_MESSAGE_CURSOR);
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ChatBusinessException(ChatErrorCode.INVALID_CHAT_MESSAGE_PAGE_SIZE);
        }
    }

    private MessageItem toMessageItem(ChatMessage message, Long viewerParticipantId) {
        boolean mine = viewerParticipantId.equals(message.getSenderParticipantId());
        boolean deleted = mine
            ? message.getSenderDeletedAt() != null
            : message.getReceiverDeletedAt() != null;

        return new MessageItem(
            message.getId(), mine, message.getMessageType(),
            deleted ? DELETED_CONTENT : message.getTextContent(),
            message.getStatus(), message.getCreatedAt());
    }

    public record MessagePage(
        Long chatRoomId,
        ChatRoomStatus roomStatus,
        boolean chatNotification,
        Long otherUserId,
        List<MessageItem> messages,
        Long nextCursor,
        boolean hasNext) {}

    public record MessageItem(
        Long messageId,
        boolean mine,
        ChatMessageType messageType,
        String textContent,
        ChatMessageStatus status,
        LocalDateTime createdAt) {}
}
