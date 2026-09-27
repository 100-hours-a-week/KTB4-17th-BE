package com.team.dating_backend.aipractice.client;

import com.team.dating_backend.aipractice.dto.ai.AiPracticeAiPayloads.GenerationReplyResponse;

public interface AiPracticeAiClient {

    String startSession(Long partnerMemberId);

    GenerationReplyResponse sendMessage(String aiSessionId, String userMessage);

    GenerationReplyResponse retryMessage(String aiSessionId);

    void endSession(String aiSessionId);
}
