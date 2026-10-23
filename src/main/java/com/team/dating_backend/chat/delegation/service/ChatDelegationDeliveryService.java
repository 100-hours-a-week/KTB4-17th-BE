package com.team.dating_backend.chat.delegation.service;

import com.team.dating_backend.chat.delegation.entity.ChatDelegation;
import com.team.dating_backend.chat.delegation.entity.ChatDelegationMessage;
import com.team.dating_backend.chat.delegation.enums.ChatDelegationMessageStatus;
import com.team.dating_backend.chat.delegation.enums.ChatDelegationStatus;
import com.team.dating_backend.chat.delegation.repository.ChatDelegationMessageLockTarget;
import com.team.dating_backend.chat.delegation.repository.ChatDelegationMessageRepository;
import com.team.dating_backend.chat.delegation.repository.ChatDelegationRepository;
import com.team.dating_backend.chat.entity.ChatMessage;
import com.team.dating_backend.chat.entity.ChatMessageOutbox;
import com.team.dating_backend.chat.entity.ChatRoom;
import com.team.dating_backend.chat.enums.ChatParticipantStatus;
import com.team.dating_backend.chat.enums.ChatRoomEndReason;
import com.team.dating_backend.chat.enums.ChatRoomStatus;
import com.team.dating_backend.chat.repository.ChatMessageOutboxRepository;
import com.team.dating_backend.chat.repository.ChatMessageRepository;
import com.team.dating_backend.chat.repository.ChatRoomRepository;
import com.team.dating_backend.matching.entity.Match;
import com.team.dating_backend.matching.enums.MatchEndReason;
import com.team.dating_backend.matching.repository.MatchRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatDelegationDeliveryService {

    private static final int FINAL_TURN = 3;

    private final ChatDelegationMessageRepository delegationMessageRepository;
    private final ChatDelegationRepository delegationRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatMessageOutboxRepository outboxRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final MatchRepository matchRepository;
    private final ChatDelegationSchedulePolicy schedulePolicy;

    @Transactional
    public boolean deliver(Long delegationMessageId, LocalDateTime now) {
        ChatDelegationMessageLockTarget lockTarget = delegationMessageRepository
            .findLockTargetById(delegationMessageId)
            .orElse(null);
        if (lockTarget == null) {
            return false;
        }

        Match userMatch = lockTarget.turn() == FINAL_TURN
            ? matchRepository.findByIdForUpdate(lockTarget.matchId()).orElse(null)
            : null;
        ChatRoom room = chatRoomRepository.findWithLockById(lockTarget.chatRoomId())
            .orElse(null);
        ChatDelegation delegation = delegationRepository
            .findWithLockById(lockTarget.delegationId())
            .orElse(null);
        ChatDelegationMessage delegationMessage = delegationMessageRepository
            .findWithLockById(delegationMessageId)
            .orElse(null);
        if (!isDeliverable(lockTarget, userMatch, room, delegation, delegationMessage)) {
            cancelUnavailable(delegation, delegationMessage, room);
            return false;
        }

        ChatMessage chatMessage = chatMessageRepository.save(new ChatMessage(
            room,
            delegation.getRequesterParticipant(),
            delegationMessage.getRequestId(),
            delegationMessage.getContent(),
            now));
        outboxRepository.save(new ChatMessageOutbox(chatMessage.getId(), now));
        delegationMessage.markSent(chatMessage);
        if (delegationMessage.getTurn() == FINAL_TURN) {
            delegation.complete();
            userMatch.end(MatchEndReason.AI_DELEGATION_COMPLETED, now);
            room.end(ChatRoomEndReason.AI_DELEGATION_COMPLETED, now);
        } else {
            delegation.reschedule(schedulePolicy.nextActionAt(
                chatMessage, delegation.getRequesterParticipant().getId(), now));
        }
        return true;
    }

    private boolean isDeliverable(
        ChatDelegationMessageLockTarget lockTarget,
        Match userMatch,
        ChatRoom room,
        ChatDelegation delegation,
        ChatDelegationMessage message) {
        boolean matchReady = lockTarget.turn() != FINAL_TURN
            || userMatch != null && room != null
                && room.getMatch().getId().equals(userMatch.getId());
        return matchReady
            && room != null
            && delegation != null
            && message != null
            && delegation.getChatRoom().getId().equals(room.getId())
            && message.getDelegation().getId().equals(delegation.getId())
            && delegation.getStatus() == ChatDelegationStatus.IN_PROGRESS
            && message.getStatus() == ChatDelegationMessageStatus.READY
            && room.getStatus() == ChatRoomStatus.ACTIVE
            && room.getParticipants().size() == 2
            && room.getParticipants().stream()
                .allMatch(participant -> participant.getStatus()
                    == ChatParticipantStatus.ACTIVE);
    }

    private void cancelUnavailable(
        ChatDelegation delegation,
        ChatDelegationMessage message,
        ChatRoom room) {
        if (message != null) {
            message.cancel();
        }
        if (delegation != null
            && delegation.isActionRestricted()
            && (room == null || room.getStatus() != ChatRoomStatus.ACTIVE)) {
            delegation.interrupt();
        }
    }

}
