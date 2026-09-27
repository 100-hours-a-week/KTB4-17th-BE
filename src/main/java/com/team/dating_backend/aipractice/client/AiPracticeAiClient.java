package com.team.dating_backend.aipractice.client;

import com.team.dating_backend.aipractice.dto.ai.AiPracticeAiPayloads.ContinueGenerationRequest;
import com.team.dating_backend.aipractice.dto.ai.AiPracticeAiPayloads.InitialGenerationRequest;

public interface AiPracticeAiClient {

    String startGeneration(InitialGenerationRequest request, String idempotencyKey);

    void continueGeneration(
        String aiSessionId, ContinueGenerationRequest request, String idempotencyKey);

    void endSession(String aiSessionId, String idempotencyKey);
}
