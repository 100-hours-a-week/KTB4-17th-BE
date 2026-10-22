package com.team.dating_backend.chat.delegation.service;

import com.team.dating_backend.chat.delegation.entity.ChatDelegation;
import com.team.dating_backend.chat.delegation.entity.ChatDelegationMessage;
import com.team.dating_backend.chat.delegation.enums.ChatDelegationMessageStatus;
import com.team.dating_backend.chat.delegation.enums.ChatDelegationStatus;
import com.team.dating_backend.chat.delegation.repository.ChatDelegationMessageRepository;
import com.team.dating_backend.chat.delegation.repository.ChatDelegationRepository;
import com.team.dating_backend.chat.entity.ChatMessage;
import com.team.dating_backend.chat.entity.ChatMessageOutbox;
import com.team.dating_backend.chat.repository.ChatMessageOutboxRepository;
import com.team.dating_backend.chat.repository.ChatMessageRepository;
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

    @Transactional
    public boolean deliver(Long delegationMessageId, LocalDateTime now) {
        ChatDelegationMessage delegationMessage = delegationMessageRepository
            .findWithLockById(delegationMessageId)
            .orElse(null);
        if (delegationMessage == null
            || delegationMessage.getStatus() != ChatDelegationMessageStatus.READY) {
            return false;
        }

        ChatDelegation delegation = delegationRepository
            .findWithLockById(delegationMessage.getDelegation().getId())
            .orElse(null);
        if (delegation == null || delegation.getStatus() != ChatDelegationStatus.IN_PROGRESS) {
            return false;
        }

        ChatMessage chatMessage = chatMessageRepository.save(new ChatMessage(
            delegation.getChatRoom(),
            delegation.getRequesterParticipant(),
            delegationMessage.getRequestId(),
            delegationMessage.getContent(),
            now));
        outboxRepository.save(new ChatMessageOutbox(chatMessage.getId(), now));
        delegationMessage.markSent(chatMessage);
        if (delegationMessage.getTurn() == FINAL_TURN) {
            delegation.complete();
        }
        return true;
    }
}
