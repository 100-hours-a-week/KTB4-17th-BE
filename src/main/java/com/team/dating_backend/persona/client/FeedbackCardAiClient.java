package com.team.dating_backend.persona.client;

import com.team.dating_backend.persona.dto.ai.FeedbackCardAiPayloads.AnswerRequest;
import com.team.dating_backend.persona.dto.ai.FeedbackCardAiPayloads.AnswerResponse;
import com.team.dating_backend.persona.dto.ai.FeedbackCardAiPayloads.CardResponse;

public interface FeedbackCardAiClient {

    CardResponse next(String userId);

    AnswerResponse answer(String cardId, AnswerRequest request);
}
