package com.team.dating_backend.chat.service;

import com.team.dating_backend.chat.dto.ai.ChatDelegationAiPayloads.DraftRequest;
import com.team.dating_backend.chat.dto.ai.ChatDelegationAiPayloads.RecentMessage;
import com.team.dating_backend.chat.dto.ai.ChatDelegationAiPayloads.Speaker;
import com.team.dating_backend.chat.entity.ChatDelegation;
import com.team.dating_backend.chat.entity.ChatMessage;
import com.team.dating_backend.chat.entity.ChatParticipant;
import com.team.dating_backend.chat.entity.ChatRoom;
import com.team.dating_backend.chat.enums.ChatDelegationEndType;
import com.team.dating_backend.chat.enums.ChatDelegationStatus;
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
        ChatRoom room = requireRoom(chatRoomId);
        ChatParticipant requester = requireRequester(room, requesterUserId);
        validateRoom(room, requester);
        ChatDelegation existing = delegationRepository.findByChatRoomId(chatRoomId).orElse(null);
        if (existing != null) {
            if (!existing.getRequesterParticipant().getId().equals(requester.getId())) {
                throw failure(ChatErrorCode.CHAT_ACCESS_DENIED);
            }
            if (existing.getStatus() != ChatDelegationStatus.DRAFT_FAILED) {
                throw failure(ChatErrorCode.CHAT_DELEGATION_ALREADY_EXISTS);
            }
        }

        MessageContext context = messageContext(chatRoomId, requester.getId());
        UUID requestId = UUID.randomUUID();
        if (existing != null) {
            existing.regenerate(endType, requestId, context.messageId());
            return preparation(existing.getId(), requestId, room.getId(), requesterUserId,
                endType, context);
        }
        ChatDelegation delegation = delegationRepository.saveAndFlush(
            new ChatDelegation(room, requester, endType, requestId, context.messageId()));
        return preparation(delegation.getId(), requestId, room.getId(), requesterUserId,
            endType, context);
    }

    @Transactional
    public Preparation prepareRegeneration(Long chatRoomId, Long delegationId,
        Long requesterUserId, ChatDelegationEndType endType) {
        ChatRoom room = requireRoom(chatRoomId);
        ChatParticipant requester = requireRequester(room, requesterUserId);
        validateRoom(room, requester);
        ChatDelegation delegation = delegationRepository.findWithLockById(delegationId)
            .filter(found -> found.getChatRoom().getId().equals(chatRoomId))
            .orElseThrow(() -> failure(ChatErrorCode.CHAT_DELEGATION_NOT_FOUND));
        if (!delegation.getRequesterParticipant().getId().equals(requester.getId())) {
            throw failure(ChatErrorCode.CHAT_ACCESS_DENIED);
        }
        if (delegation.getStatus() != ChatDelegationStatus.AWAITING_CONFIRMATION
            && delegation.getStatus() != ChatDelegationStatus.DRAFT_FAILED) {
            throw failure(ChatErrorCode.CHAT_DELEGATION_INVALID_STATUS);
        }

        MessageContext context = messageContext(chatRoomId, requester.getId());
        UUID requestId = UUID.randomUUID();
        delegation.regenerate(endType, requestId, context.messageId());
        return preparation(
            delegationId, requestId, chatRoomId, requesterUserId, endType, context);
    }

    private Preparation preparation(Long delegationId, UUID requestId, Long roomId,
        Long requesterUserId, ChatDelegationEndType endType, MessageContext context) {
        return new Preparation(delegationId, requestId, new DraftRequest(
            requestId, roomId, delegationId, requesterUserId.toString(),
            endType, context.messages()));
    }

    @Transactional
    public void complete(Preparation preparation, String draftId) {
        requireDelegation(preparation.delegationId())
            .completeDraft(preparation.requestId(), draftId);
    }

    @Transactional
    public void fail(Preparation preparation) {
        requireDelegation(preparation.delegationId()).failDraft(preparation.requestId());
    }

    private ChatRoom requireRoom(Long chatRoomId) {
        return chatRoomRepository.findWithLockById(chatRoomId)
            .orElseThrow(() -> failure(ChatErrorCode.CHAT_ROOM_NOT_FOUND));
    }

    private ChatParticipant requireRequester(ChatRoom room, Long requesterUserId) {
        ChatParticipant requester = room.getParticipants().stream()
            .filter(participant -> participant.getUserId().equals(requesterUserId))
            .findFirst()
            .orElseThrow(() -> failure(ChatErrorCode.CHAT_ACCESS_DENIED));
        if (requester.getStatus() != ChatParticipantStatus.ACTIVE) {
            throw failure(ChatErrorCode.CHAT_ACCESS_DENIED);
        }
        return requester;
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
        return delegationRepository.findWithLockById(delegationId)
            .orElseThrow(() -> failure(ChatErrorCode.INTERNAL_SERVER_ERROR));
    }

    private MessageContext messageContext(Long chatRoomId, Long requesterParticipantId) {
        List<ChatMessage> messages = new ArrayList<>(chatMessageRepository
            .findByChatRoomIdAndStatusOrderByIdDesc(
                chatRoomId, ChatMessageStatus.SENT, PageRequest.of(0, RECENT_MESSAGE_LIMIT)));
        if (messages.isEmpty()) {
            throw failure(ChatErrorCode.CHAT_DELEGATION_CONVERSATION_REQUIRED);
        }
        Long messageId = messages.getFirst().getId();
        Collections.reverse(messages);
        return new MessageContext(messageId, messages.stream()
            .map(message -> toRecentMessage(message, requesterParticipantId))
            .toList());
    }

    private ChatBusinessException failure(ChatErrorCode code) {
        return new ChatBusinessException(code);
    }

    public record Preparation(Long delegationId, UUID requestId, DraftRequest aiRequest) {}

    private record MessageContext(Long messageId, List<RecentMessage> messages) {}
}
