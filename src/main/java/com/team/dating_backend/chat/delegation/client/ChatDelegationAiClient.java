package com.team.dating_backend.chat.delegation.client;

import com.team.dating_backend.chat.delegation.dto.ai.ChatDelegationAiPayloads.ConfirmResponse;
import com.team.dating_backend.chat.delegation.dto.ai.ChatDelegationAiPayloads.DraftRequest;
import com.team.dating_backend.chat.delegation.dto.ai.ChatDelegationAiPayloads.DraftResponse;

public interface ChatDelegationAiClient {

    DraftResponse createDraft(DraftRequest request);

    ConfirmResponse confirmDraft(String draftId, Long delegationId);
}
