package com.team.dating_backend.chat.delegation.service;

import com.team.dating_backend.chat.delegation.entity.ChatDelegation;
import com.team.dating_backend.chat.delegation.repository.ChatDelegationRepository;
import com.team.dating_backend.chat.enums.ChatErrorCode;
import com.team.dating_backend.chat.exception.ChatBusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatDelegationParticipantService {

    private final ChatDelegationRepository delegationRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    public void validateMessageSend(Long chatRoomId, Long senderParticipantId) {
        ChatDelegation delegation = delegationRepository
            .findWithLockByChatRoomId(chatRoomId)
            .orElse(null);
        if (isRestrictedRequester(delegation, senderParticipantId)) {
            throw actionNotAllowed();
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void handleLeave(Long chatRoomId, Long leavingParticipantId) {
        ChatDelegation delegation = delegationRepository
            .findWithLockByChatRoomId(chatRoomId)
            .orElse(null);
        if (isRestrictedRequester(delegation, leavingParticipantId)) {
            throw actionNotAllowed();
        }
    }

    private boolean isRestrictedRequester(ChatDelegation delegation, Long participantId) {
        return delegation != null
            && delegation.isActionRestricted()
            && delegation.getRequesterParticipant().getId().equals(participantId);
    }

    private ChatBusinessException actionNotAllowed() {
        return new ChatBusinessException(ChatErrorCode.CHAT_DELEGATION_ACTION_NOT_ALLOWED);
    }
}
