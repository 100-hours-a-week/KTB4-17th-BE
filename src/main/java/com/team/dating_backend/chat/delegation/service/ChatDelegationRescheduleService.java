package com.team.dating_backend.chat.delegation.service;

import com.team.dating_backend.chat.delegation.entity.ChatDelegation;
import com.team.dating_backend.chat.delegation.enums.ChatDelegationStatus;
import com.team.dating_backend.chat.delegation.repository.ChatDelegationRepository;
import com.team.dating_backend.chat.entity.ChatMessage;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatDelegationRescheduleService {

    private final ChatDelegationRepository delegationRepository;
    private final ChatDelegationSchedulePolicy schedulePolicy;

    @Transactional(propagation = Propagation.MANDATORY)
    public void rescheduleForNewMessage(ChatMessage message) {
        ChatDelegation delegation = delegationRepository
            .findWithLockByChatRoomId(message.getChatRoomId())
            .orElse(null);
        if (delegation == null
            || delegation.getStatus() != ChatDelegationStatus.IN_PROGRESS
            || delegation.getRequesterParticipant().getId()
                .equals(message.getSenderParticipantId())) {
            return;
        }

        LocalDateTime nextActionAt = schedulePolicy.nextActionAt(
            message, delegation.getRequesterParticipant().getId(), message.getCreatedAt());
        delegation.reschedule(nextActionAt);
    }
}
