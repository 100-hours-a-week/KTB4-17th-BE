package com.team.dating_backend.chat.delegation.service;

import com.team.dating_backend.chat.delegation.dto.response.ChatDelegationStartResponse;
import com.team.dating_backend.chat.delegation.entity.ChatDelegation;
import com.team.dating_backend.chat.entity.ChatMessage;
import com.team.dating_backend.chat.entity.ChatParticipant;
import com.team.dating_backend.chat.entity.ChatRoom;
import com.team.dating_backend.chat.delegation.enums.ChatDelegationStatus;
import com.team.dating_backend.chat.enums.ChatErrorCode;
import com.team.dating_backend.chat.enums.ChatMessageStatus;
import com.team.dating_backend.chat.enums.ChatParticipantStatus;
import com.team.dating_backend.chat.enums.ChatRoomStatus;
import com.team.dating_backend.chat.exception.ChatBusinessException;
import com.team.dating_backend.chat.delegation.repository.ChatDelegationRepository;
import com.team.dating_backend.chat.repository.ChatMessageRepository;
import com.team.dating_backend.chat.repository.ChatRoomRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatDelegationStartPersistenceService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatDelegationRepository delegationRepository;
    private final ChatDelegationSchedulePolicy schedulePolicy;

    @Transactional
    public Confirmation prepare(
        Long chatRoomId, Long delegationId, Long requesterUserId, String draftId) {
        ChatRoom room = requireRoom(chatRoomId);
        ChatParticipant requester = requireRequester(room, requesterUserId);
        validateRoom(room, requester);
        ChatDelegation delegation = requireDelegation(chatRoomId, delegationId);
        validateOwner(delegation, requester);
        validateDraft(delegation, draftId);
        if (delegation.getStatus() == ChatDelegationStatus.IN_PROGRESS) {
            return new Confirmation(delegationId, draftId, true);
        }
        if (delegation.getStatus() != ChatDelegationStatus.AWAITING_CONFIRMATION) {
            throw failure(ChatErrorCode.CHAT_DELEGATION_INVALID_STATUS);
        }
        return new Confirmation(delegationId, draftId, false);
    }

    @Transactional
    public ChatDelegationStartResponse start(
        Long chatRoomId, Long requesterUserId, Confirmation confirmation,
        LocalDateTime approvedAt) {
        ChatRoom room = requireRoom(chatRoomId);
        ChatParticipant requester = requireRequester(room, requesterUserId);
        validateRoom(room, requester);
        ChatDelegation delegation = requireDelegation(chatRoomId, confirmation.delegationId());
        validateOwner(delegation, requester);
        validateDraft(delegation, confirmation.draftId());
        if (delegation.getStatus() == ChatDelegationStatus.IN_PROGRESS) {
            return response(delegation);
        }
        if (delegation.getStatus() != ChatDelegationStatus.AWAITING_CONFIRMATION) {
            throw failure(ChatErrorCode.CHAT_DELEGATION_INVALID_STATUS);
        }

        ChatMessage latestMessage = chatMessageRepository
            .findRecentByChatRoomIdAndStatus(
                chatRoomId, ChatMessageStatus.SENT, Limit.of(1))
            .stream()
            .findFirst()
            .orElseThrow(() -> failure(ChatErrorCode.CHAT_DELEGATION_CONVERSATION_REQUIRED));
        LocalDateTime nextActionAt = schedulePolicy.nextActionAt(
            latestMessage, requester.getId(), approvedAt);
        delegation.start(confirmation.draftId(), approvedAt, nextActionAt);
        return response(delegation);
    }

    private ChatRoom requireRoom(Long chatRoomId) {
        return chatRoomRepository.findWithLockById(chatRoomId)
            .orElseThrow(() -> failure(ChatErrorCode.CHAT_ROOM_NOT_FOUND));
    }

    private ChatDelegation requireDelegation(Long chatRoomId, Long delegationId) {
        return delegationRepository.findWithLockById(delegationId)
            .filter(delegation -> delegation.getChatRoom().getId().equals(chatRoomId))
            .orElseThrow(() -> failure(ChatErrorCode.CHAT_DELEGATION_NOT_FOUND));
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

    private void validateOwner(ChatDelegation delegation, ChatParticipant requester) {
        if (!delegation.getRequesterParticipant().getId().equals(requester.getId())) {
            throw failure(ChatErrorCode.CHAT_ACCESS_DENIED);
        }
    }

    private void validateDraft(ChatDelegation delegation, String draftId) {
        if (!Objects.equals(delegation.getAiDraftId(), draftId)) {
            throw failure(ChatErrorCode.CHAT_DELEGATION_DRAFT_MISMATCH);
        }
    }

    private ChatDelegationStartResponse response(ChatDelegation delegation) {
        return new ChatDelegationStartResponse(delegation.getId(), delegation.getStatus());
    }

    private ChatBusinessException failure(ChatErrorCode code) {
        return new ChatBusinessException(code);
    }

    public record Confirmation(Long delegationId, String draftId, boolean alreadyStarted) {}
}
