package com.team.dating_backend.chat.client;

import com.team.dating_backend.chat.dto.ai.ChatDelegationAiPayloads.DraftRequest;
import com.team.dating_backend.chat.dto.ai.ChatDelegationAiPayloads.DraftResponse;

public interface ChatDelegationAiClient {

    DraftResponse createDraft(DraftRequest request);
}
