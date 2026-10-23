package com.team.dating_backend.chat.delegation.service;

import com.team.dating_backend.chat.delegation.entity.ChatDelegation;
import com.team.dating_backend.chat.delegation.enums.ChatDelegationStatus;
import com.team.dating_backend.chat.delegation.repository.ChatDelegationLockTarget;
import com.team.dating_backend.chat.delegation.repository.ChatDelegationRepository;
import com.team.dating_backend.chat.entity.ChatMessage;
import com.team.dating_backend.chat.entity.ChatRoom;
import com.team.dating_backend.chat.enums.ChatMessageStatus;
import com.team.dating_backend.chat.enums.ChatParticipantStatus;
import com.team.dating_backend.chat.enums.ChatRoomEndReason;
import com.team.dating_backend.chat.enums.ChatRoomStatus;
import com.team.dating_backend.chat.repository.ChatMessageRepository;
import com.team.dating_backend.chat.repository.ChatRoomRepository;
import com.team.dating_backend.matching.entity.Match;
import com.team.dating_backend.matching.enums.MatchEndReason;
import com.team.dating_backend.matching.repository.MatchRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatDelegationTerminationService {

    private static final int RESPONSE_TIMEOUT_HOURS = 24;

    private final ChatDelegationRepository delegationRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final MatchRepository matchRepository;

    @Transactional
    public boolean completeIfNoResponse(Long delegationId, LocalDateTime now) {
        ChatDelegationLockTarget lockTarget = delegationRepository
            .findLockTargetById(delegationId)
            .orElse(null);
        if (lockTarget == null) {
            return true;
        }

        Match userMatch = matchRepository.findByIdForUpdate(lockTarget.matchId())
            .orElse(null);
        ChatRoom room = chatRoomRepository.findWithLockById(lockTarget.chatRoomId())
            .orElse(null);
        ChatDelegation delegation = delegationRepository.findWithLockById(delegationId)
            .orElse(null);
        if (!matches(lockTarget, userMatch, room, delegation)
            || delegation.getStatus() != ChatDelegationStatus.IN_PROGRESS
            || delegation.getNextActionAt() == null
            || delegation.getNextActionAt().isAfter(now)) {
            return true;
        }
        if (!isActive(room)) {
            return true;
        }

        ChatMessage latestMessage = chatMessageRepository.findRecentByChatRoomIdAndStatus(
                room.getId(), ChatMessageStatus.SENT, Limit.of(1))
            .stream()
            .findFirst()
            .orElseThrow(IllegalStateException::new);
        Long requesterParticipantId = delegation.getRequesterParticipant().getId();
        if (!requesterParticipantId.equals(latestMessage.getSenderParticipantId())) {
            return false;
        }

        LocalDateTime responseDeadline = latestMessage.getCreatedAt()
            .plusHours(RESPONSE_TIMEOUT_HOURS);
        if (responseDeadline.isAfter(now)) {
            delegation.reschedule(responseDeadline);
            return true;
        }

        delegation.complete();
        userMatch.end(MatchEndReason.AI_DELEGATION_COMPLETED, now);
        room.end(ChatRoomEndReason.AI_DELEGATION_COMPLETED, now);
        return true;
    }

    private boolean matches(
        ChatDelegationLockTarget lockTarget,
        Match userMatch,
        ChatRoom room,
        ChatDelegation delegation) {
        return userMatch != null
            && room != null
            && delegation != null
            && room.getMatch().getId().equals(userMatch.getId())
            && room.getId().equals(lockTarget.chatRoomId())
            && delegation.getChatRoom().getId().equals(room.getId());
    }

    private boolean isActive(ChatRoom room) {
        return room.getStatus() == ChatRoomStatus.ACTIVE
            && room.getParticipants().size() == 2
            && room.getParticipants().stream()
                .allMatch(participant -> participant.getStatus()
                    == ChatParticipantStatus.ACTIVE);
    }
}
