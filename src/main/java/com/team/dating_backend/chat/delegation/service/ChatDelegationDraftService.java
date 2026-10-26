package com.team.dating_backend.chat.delegation.service;

import com.team.dating_backend.chat.delegation.client.ChatDelegationAiClient;
import com.team.dating_backend.chat.delegation.dto.ai.ChatDelegationAiPayloads.DraftResponse;
import com.team.dating_backend.chat.delegation.dto.response.ChatDelegationDraftResponse;
import com.team.dating_backend.chat.delegation.enums.ChatDelegationEndType;
import com.team.dating_backend.chat.delegation.enums.ChatDelegationStatus;
import com.team.dating_backend.chat.exception.ChatBusinessException;
import com.team.dating_backend.chat.delegation.service.ChatDelegationDraftPersistenceService.Preparation;
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
        DraftResponse aiResponse;
        try {
            aiResponse = aiClient.createDraft(preparation.aiRequest());
        } catch (ChatBusinessException exception) {
            persistenceService.fail(preparation);
            throw exception;
        }
        return complete(preparation, aiResponse);
    }

    public ChatDelegationDraftResponse regenerate(Long chatRoomId, Long delegationId,
        Long requesterUserId, ChatDelegationEndType endType) {
        Preparation preparation = persistenceService.prepareRegeneration(
            chatRoomId, delegationId, requesterUserId, endType);
        DraftResponse aiResponse;
        try {
            aiResponse = aiClient.createDraft(preparation.aiRequest());
        } catch (ChatBusinessException exception) {
            persistenceService.fail(preparation);
            throw exception;
        }
        return complete(preparation, aiResponse);
    }

    private ChatDelegationDraftResponse complete(
        Preparation preparation, DraftResponse aiResponse) {
        persistenceService.complete(preparation, aiResponse.draftId());
        return new ChatDelegationDraftResponse(
            preparation.delegationId(), ChatDelegationStatus.AWAITING_CONFIRMATION,
            aiResponse.draftId(), aiResponse.turns().stream()
                .map(turn -> new ChatDelegationDraftResponse.Turn(
                    turn.turn(), turn.message()))
                .toList());
    }
}
