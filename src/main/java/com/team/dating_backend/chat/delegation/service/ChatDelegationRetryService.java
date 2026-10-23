package com.team.dating_backend.chat.delegation.service;

import com.team.dating_backend.chat.delegation.dto.response.ChatDelegationStartResponse;
import com.team.dating_backend.chat.delegation.entity.ChatDelegation;
import com.team.dating_backend.chat.delegation.entity.ChatDelegationMessage;
import com.team.dating_backend.chat.delegation.enums.ChatDelegationMessageStatus;
import com.team.dating_backend.chat.delegation.enums.ChatDelegationStatus;
import com.team.dating_backend.chat.delegation.repository.ChatDelegationMessageRepository;
import com.team.dating_backend.chat.delegation.repository.ChatDelegationRepository;
import com.team.dating_backend.chat.delegation.service.ChatDelegationConversationService.MessageContext;
import com.team.dating_backend.chat.entity.ChatParticipant;
import com.team.dating_backend.chat.entity.ChatRoom;
import com.team.dating_backend.chat.enums.ChatErrorCode;
import com.team.dating_backend.chat.enums.ChatParticipantStatus;
import com.team.dating_backend.chat.enums.ChatRoomStatus;
import com.team.dating_backend.chat.exception.ChatBusinessException;
import com.team.dating_backend.chat.repository.ChatRoomRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatDelegationRetryService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatDelegationRepository delegationRepository;
    private final ChatDelegationMessageRepository messageRepository;
    private final ChatDelegationConversationService conversationService;

    @Transactional
    public ChatDelegationStartResponse retry(
        Long chatRoomId,
        Long delegationId,
        Long requesterUserId) {
        LocalDateTime now = LocalDateTime.now();
        ChatRoom room = chatRoomRepository.findWithLockById(chatRoomId)
            .orElseThrow(() -> failure(ChatErrorCode.CHAT_ROOM_NOT_FOUND));
        ChatDelegation delegation = delegationRepository.findWithLockById(delegationId)
            .filter(found -> found.getChatRoom().getId().equals(room.getId()))
            .orElseThrow(() -> failure(ChatErrorCode.CHAT_DELEGATION_NOT_FOUND));
        ChatParticipant requester = requireRequester(
            room, delegation, requesterUserId);
        validateRetryable(room, delegation, requester);

        ChatDelegationMessage failedMessage = messageRepository
            .findAllWithLockByDelegationIdOrderByTurnDesc(delegationId)
            .stream()
            .filter(message -> message.getStatus() == ChatDelegationMessageStatus.FAILED)
            .findFirst()
            .orElseThrow(() -> failure(ChatErrorCode.CHAT_DELEGATION_INVALID_STATUS));
        MessageContext context = conversationService.latest(
            room.getId(), requester.getId());

        failedMessage.retryManually(UUID.randomUUID(), context.messageId(), now);
        delegation.retry();
        return new ChatDelegationStartResponse(
            delegation.getId(), delegation.getStatus());
    }

    private ChatParticipant requireRequester(
        ChatRoom room,
        ChatDelegation delegation,
        Long requesterUserId) {
        ChatParticipant requester = room.getParticipants().stream()
            .filter(participant -> participant.getUserId().equals(requesterUserId))
            .findFirst()
            .orElseThrow(() -> failure(ChatErrorCode.CHAT_ACCESS_DENIED));
        if (!delegation.getRequesterParticipant().getId().equals(requester.getId())) {
            throw failure(ChatErrorCode.CHAT_ACCESS_DENIED);
        }
        return requester;
    }

    private void validateRetryable(
        ChatRoom room,
        ChatDelegation delegation,
        ChatParticipant requester) {
        List<ChatParticipant> participants = room.getParticipants();
        boolean allParticipantsActive = participants.size() == 2
            && participants.stream().allMatch(participant -> participant.getStatus() == ChatParticipantStatus.ACTIVE);
        if (room.getStatus() != ChatRoomStatus.ACTIVE || !allParticipantsActive) {
            throw failure(ChatErrorCode.CHAT_ROOM_NOT_ACTIVE);
        }
        boolean retryable = delegation.getStatus() == ChatDelegationStatus.RETRY_REQUIRED
            || delegation.getStatus() == ChatDelegationStatus.FAILED;
        if (requester.getStatus() != ChatParticipantStatus.ACTIVE || !retryable) {
            throw failure(ChatErrorCode.CHAT_DELEGATION_INVALID_STATUS);
        }
    }

    private ChatBusinessException failure(ChatErrorCode code) {
        return new ChatBusinessException(code);
    }
}
