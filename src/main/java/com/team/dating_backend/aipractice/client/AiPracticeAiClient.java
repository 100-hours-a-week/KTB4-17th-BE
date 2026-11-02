package com.team.dating_backend.aipractice.client;

import com.team.dating_backend.aipractice.dto.ai.AiPracticeAiPayloads.GenerationReplyResponse;
import java.util.UUID;

public interface AiPracticeAiClient {

    String startSession(Long meUserId, Long partnerUserId, UUID idempotencyKey);

    GenerationReplyResponse sendMessage(
        String aiSessionId, String userMessage, UUID idempotencyKey);

    GenerationReplyResponse retryMessage(String aiSessionId, UUID idempotencyKey);

    void endSession(String aiSessionId, UUID idempotencyKey);
}
