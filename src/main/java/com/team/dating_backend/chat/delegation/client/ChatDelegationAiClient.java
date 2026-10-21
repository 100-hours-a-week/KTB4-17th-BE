package com.team.dating_backend.chat.delegation.client;

import com.team.dating_backend.chat.delegation.dto.ai.ChatDelegationAiPayloads.ConfirmResponse;
import com.team.dating_backend.chat.delegation.dto.ai.ChatDelegationAiPayloads.DraftRequest;
import com.team.dating_backend.chat.delegation.dto.ai.ChatDelegationAiPayloads.DraftResponse;
import com.team.dating_backend.chat.delegation.dto.ai.ChatDelegationAiPayloads.MessageRequest;
import com.team.dating_backend.chat.delegation.dto.ai.ChatDelegationAiPayloads.MessageResponse;

public interface ChatDelegationAiClient {

    DraftResponse createDraft(DraftRequest request);

    ConfirmResponse confirmDraft(String draftId, Long delegationId);

    MessageResponse createMessage(MessageRequest request, int expectedTurn);
}
