package com.team.dating_backend.chat.delegation.service;

import com.team.dating_backend.chat.delegation.client.ChatDelegationAiClient;
import com.team.dating_backend.chat.delegation.dto.response.ChatDelegationStartResponse;
import com.team.dating_backend.chat.delegation.enums.ChatDelegationStatus;
import com.team.dating_backend.chat.delegation.service.ChatDelegationStartPersistenceService.Confirmation;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ChatDelegationStartService {

    private final ChatDelegationStartPersistenceService persistenceService;
    private final ChatDelegationAiClient aiClient;

    public ChatDelegationStartResponse start(
        Long chatRoomId, Long delegationId, Long requesterUserId, String draftId) {
        Confirmation confirmation = persistenceService.prepare(
            chatRoomId, delegationId, requesterUserId, draftId);
        if (confirmation.alreadyStarted()) {
            return new ChatDelegationStartResponse(
                delegationId, ChatDelegationStatus.IN_PROGRESS);
        }
        aiClient.confirmDraft(draftId, delegationId);
        return persistenceService.start(
            chatRoomId, requesterUserId, confirmation, LocalDateTime.now());
    }
}
