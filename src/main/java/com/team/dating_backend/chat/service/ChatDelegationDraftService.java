package com.team.dating_backend.chat.service;

import com.team.dating_backend.chat.client.ChatDelegationAiClient;
import com.team.dating_backend.chat.dto.ai.ChatDelegationAiPayloads.DraftResponse;
import com.team.dating_backend.chat.dto.response.ChatDelegationDraftResponse;
import com.team.dating_backend.chat.enums.ChatDelegationEndType;
import com.team.dating_backend.chat.enums.ChatDelegationStatus;
import com.team.dating_backend.chat.service.ChatDelegationDraftPersistenceService.Preparation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ChatDelegationDraftService {

    private final ChatDelegationDraftPersistenceService persistenceService;
    private final ChatDelegationAiClient aiClient;

    public ChatDelegationDraftResponse create(
        Long chatRoomId, Long requesterUserId, ChatDelegationEndType endType) {
        Preparation preparation = persistenceService.prepare(
            chatRoomId, requesterUserId, endType);
        DraftResponse aiResponse = aiClient.createDraft(preparation.aiRequest());
        persistenceService.complete(preparation.delegationId(), aiResponse.draftId());
        return new ChatDelegationDraftResponse(
            preparation.delegationId(), ChatDelegationStatus.AWAITING_CONFIRMATION,
            aiResponse.draftId(), aiResponse.turns().stream()
                .map(turn -> new ChatDelegationDraftResponse.Turn(
                    turn.turn(), turn.message()))
                .toList());
    }
}
