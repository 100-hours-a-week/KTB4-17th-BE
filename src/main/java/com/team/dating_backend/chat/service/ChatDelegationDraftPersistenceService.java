package com.team.dating_backend.chat.service;

import com.team.dating_backend.chat.dto.ai.ChatDelegationAiPayloads.DraftRequest;
import com.team.dating_backend.chat.dto.ai.ChatDelegationAiPayloads.RecentMessage;
import com.team.dating_backend.chat.dto.ai.ChatDelegationAiPayloads.Speaker;
import com.team.dating_backend.chat.entity.ChatDelegation;
import com.team.dating_backend.chat.entity.ChatMessage;
import com.team.dating_backend.chat.entity.ChatParticipant;
import com.team.dating_backend.chat.entity.ChatRoom;
import com.team.dating_backend.chat.enums.ChatDelegationEndType;
import com.team.dating_backend.chat.enums.ChatErrorCode;
import com.team.dating_backend.chat.enums.ChatMessageStatus;
import com.team.dating_backend.chat.enums.ChatMessageType;
import com.team.dating_backend.chat.enums.ChatParticipantStatus;
import com.team.dating_backend.chat.enums.ChatRoomStatus;
import com.team.dating_backend.chat.exception.ChatBusinessException;
import com.team.dating_backend.chat.repository.ChatDelegationRepository;
import com.team.dating_backend.chat.repository.ChatMessageRepository;
import com.team.dating_backend.chat.repository.ChatRoomRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatDelegationDraftPersistenceService {

    private static final int RECENT_MESSAGE_LIMIT = 20;
    private static final String IMAGE_MESSAGE = "(사진이 전달되었습니다)";

    private final ChatRoomRepository chatRoomRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatDelegationRepository delegationRepository;

    @Transactional
    public Preparation prepare(Long chatRoomId, Long requesterUserId,
        ChatDelegationEndType endType) {
        ChatRoom room = chatRoomRepository.findWithLockById(chatRoomId)
            .orElseThrow(() -> failure(ChatErrorCode.CHAT_ROOM_NOT_FOUND));
        ChatParticipant requester = room.getParticipants().stream()
            .filter(participant -> participant.getUserId().equals(requesterUserId))
            .findFirst()
            .orElseThrow(() -> failure(ChatErrorCode.CHAT_ACCESS_DENIED));
        if (requester.getStatus() != ChatParticipantStatus.ACTIVE) {
            throw failure(ChatErrorCode.CHAT_ACCESS_DENIED);
        }
        validateRoom(room, requester);
        if (delegationRepository.existsByChatRoomId(chatRoomId)) {
            throw failure(ChatErrorCode.CHAT_DELEGATION_ALREADY_EXISTS);
        }

        List<ChatMessage> messages = new ArrayList<>(chatMessageRepository
            .findByChatRoomIdAndStatusOrderByIdDesc(
                chatRoomId, ChatMessageStatus.SENT, PageRequest.of(0, RECENT_MESSAGE_LIMIT)));
        if (messages.isEmpty()) {
            throw failure(ChatErrorCode.CHAT_DELEGATION_CONVERSATION_REQUIRED);
        }
        Long contextMessageId = messages.getFirst().getId();
        Collections.reverse(messages);

        UUID requestId = UUID.randomUUID();
        ChatDelegation delegation = delegationRepository.saveAndFlush(
            new ChatDelegation(room, requester, endType, requestId, contextMessageId));
        List<RecentMessage> recentMessages = messages.stream()
            .map(message -> toRecentMessage(message, requester.getId()))
            .toList();
        return new Preparation(delegation.getId(), new DraftRequest(
            requestId, room.getId(), delegation.getId(), requesterUserId.toString(),
            endType, recentMessages));
    }

    @Transactional
    public void complete(Long delegationId, String draftId) {
        requireDelegation(delegationId).completeDraft(draftId);
    }

    private void validateRoom(ChatRoom room, ChatParticipant requester) {
        List<ChatParticipant> participants = room.getParticipants();
        if (participants.size() != 2) {
            throw failure(ChatErrorCode.INTERNAL_SERVER_ERROR);
        }
        ChatParticipant target = participants.stream()
            .filter(participant -> !participant.getId().equals(requester.getId()))
            .findFirst()
            .orElseThrow(() -> failure(ChatErrorCode.INTERNAL_SERVER_ERROR));
        if (room.getStatus() != ChatRoomStatus.ACTIVE
            || target.getStatus() != ChatParticipantStatus.ACTIVE) {
            throw failure(ChatErrorCode.CHAT_ROOM_NOT_ACTIVE);
        }
    }

    private RecentMessage toRecentMessage(ChatMessage message, Long requesterParticipantId) {
        Speaker speaker = requesterParticipantId.equals(message.getSenderParticipantId())
            ? Speaker.REQUESTER
            : Speaker.TARGET;
        String content = message.getMessageType() == ChatMessageType.IMAGE
            ? IMAGE_MESSAGE
            : message.getTextContent();
        return new RecentMessage(speaker, content);
    }

    private ChatDelegation requireDelegation(Long delegationId) {
        return delegationRepository.findById(delegationId)
            .orElseThrow(() -> failure(ChatErrorCode.INTERNAL_SERVER_ERROR));
    }

    private ChatBusinessException failure(ChatErrorCode code) {
        return new ChatBusinessException(code);
    }

    public record Preparation(Long delegationId, DraftRequest aiRequest) {}
}
