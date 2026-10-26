package com.team.dating_backend.chat.delegation.service;

import com.team.dating_backend.chat.delegation.dto.ai.ChatDelegationAiPayloads.DraftRequest;
import com.team.dating_backend.chat.delegation.entity.ChatDelegation;
import com.team.dating_backend.chat.delegation.service.ChatDelegationConversationService.MessageContext;
import com.team.dating_backend.chat.entity.ChatParticipant;
import com.team.dating_backend.chat.entity.ChatRoom;
import com.team.dating_backend.chat.delegation.enums.ChatDelegationEndType;
import com.team.dating_backend.chat.delegation.enums.ChatDelegationStatus;
import com.team.dating_backend.chat.enums.ChatErrorCode;
import com.team.dating_backend.chat.enums.ChatParticipantStatus;
import com.team.dating_backend.chat.enums.ChatRoomStatus;
import com.team.dating_backend.chat.exception.ChatBusinessException;
import com.team.dating_backend.chat.delegation.repository.ChatDelegationRepository;
import com.team.dating_backend.chat.repository.ChatRoomRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatDelegationDraftPersistenceService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatDelegationRepository delegationRepository;
    private final ChatDelegationConversationService conversationService;

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

        MessageContext context = conversationService.latest(chatRoomId, requester.getId());
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

        MessageContext context = conversationService.latest(chatRoomId, requester.getId());
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

    private ChatDelegation requireDelegation(Long delegationId) {
        return delegationRepository.findWithLockById(delegationId)
            .orElseThrow(() -> failure(ChatErrorCode.INTERNAL_SERVER_ERROR));
    }

    private ChatBusinessException failure(ChatErrorCode code) {
        return new ChatBusinessException(code);
    }

    public record Preparation(Long delegationId, UUID requestId, DraftRequest aiRequest) {}

}
