package com.team.dating_backend.chat.service;

import com.team.dating_backend.chat.dto.event.ChatMessageReadReceiptEvent;
import com.team.dating_backend.chat.dto.event.ChatMessageReadRequestedEvent;
import com.team.dating_backend.chat.dto.request.ChatMessageReadRequest;
import com.team.dating_backend.chat.dto.response.ChatMessageReadResponse;
import com.team.dating_backend.chat.entity.ChatParticipant;
import com.team.dating_backend.chat.entity.ChatRoom;
import com.team.dating_backend.chat.enums.ChatErrorCode;
import com.team.dating_backend.chat.enums.ChatMessageStatus;
import com.team.dating_backend.chat.enums.ChatParticipantStatus;
import com.team.dating_backend.chat.enums.ChatRoomStatus;
import com.team.dating_backend.chat.exception.ChatBusinessException;
import com.team.dating_backend.chat.repository.ChatMessageRepository;
import com.team.dating_backend.chat.repository.ChatParticipantRepository;
import com.team.dating_backend.chat.repository.ChatRoomRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatMessageReadService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatParticipantRepository chatParticipantRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public ChatMessageReadResponse markAsRead(
        Long chatRoomId, Long viewerUserId, ChatMessageReadRequest request) {
        if (chatRoomId == null || chatRoomId <= 0
            || viewerUserId == null || viewerUserId <= 0
            || request == null || request.lastReadMessageId() == null
            || request.lastReadMessageId() <= 0) {
            throw new ChatBusinessException(ChatErrorCode.INVALID_CHAT_MESSAGE_READ_CURSOR);
        }

        ChatRoom room = chatRoomRepository.findById(chatRoomId)
            .orElseThrow(() -> new ChatBusinessException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));
        if (room.getStatus() == ChatRoomStatus.INACTIVE) {
            throw new ChatBusinessException(ChatErrorCode.CHAT_ACCESS_DENIED);
        }

        ChatParticipant viewer = chatParticipantRepository
            .findByChatRoomAndUserForUpdate(chatRoomId, viewerUserId)
            .orElseThrow(() -> new ChatBusinessException(ChatErrorCode.CHAT_ACCESS_DENIED));
        if (viewer.getStatus() != ChatParticipantStatus.ACTIVE) {
            throw new ChatBusinessException(ChatErrorCode.CHAT_ACCESS_DENIED);
        }

        boolean validMessage = chatMessageRepository.existsByIdAndChatRoomIdAndStatus(
            request.lastReadMessageId(), chatRoomId, ChatMessageStatus.SENT);
        if (!validMessage) {
            throw new ChatBusinessException(ChatErrorCode.INVALID_CHAT_MESSAGE_READ_CURSOR);
        }

        viewer.advanceLastReadMessageId(request.lastReadMessageId());
        Long currentCursor = viewer.getLastReadMessageId();
        publishReadReceipt(room, viewer, currentCursor);

        return new ChatMessageReadResponse(room.getId(), currentCursor);
    }

    private void publishReadReceipt(ChatRoom room, ChatParticipant viewer, Long lastReadMessageId) {
        List<ChatParticipant> others = room.getParticipants().stream()
            .filter(participant -> !participant.getId().equals(viewer.getId()))
            .filter(participant -> participant.getStatus() == ChatParticipantStatus.ACTIVE)
            .toList();
        if (others.size() > 1) {
            throw new ChatBusinessException(ChatErrorCode.INTERNAL_SERVER_ERROR);
        }
        if (others.isEmpty()) {
            return;
        }

        eventPublisher.publishEvent(new ChatMessageReadRequestedEvent(
            others.getFirst().getUserId(),
            new ChatMessageReadReceiptEvent(
                room.getId(), viewer.getUserId(), lastReadMessageId)));
    }
}
