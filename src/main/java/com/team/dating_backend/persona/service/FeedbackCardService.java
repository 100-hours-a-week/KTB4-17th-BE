package com.team.dating_backend.persona.service;

import com.team.dating_backend.persona.client.FeedbackCardAiClient;
import com.team.dating_backend.persona.dto.ai.FeedbackCardAiPayloads.AnswerRequest;
import com.team.dating_backend.persona.dto.ai.FeedbackCardAiPayloads.AnswerResponse;
import com.team.dating_backend.persona.dto.ai.FeedbackCardAiPayloads.CardResponse;
import com.team.dating_backend.persona.dto.request.FeedbackCardAnswerRequest;
import com.team.dating_backend.persona.dto.response.FeedbackCardAnswerResponse;
import com.team.dating_backend.persona.dto.response.FeedbackCardResponse;
import com.team.dating_backend.persona.enums.PersonaErrorCode;
import com.team.dating_backend.persona.exception.PersonaBusinessException;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class FeedbackCardService {

    private final FeedbackCardAiClient aiClient;

    public FeedbackCardResponse next(Long userId) {
        CardResponse response = aiClient.next(userId.toString());
        if (response == null) {
            return null;
        }
        if (!hasLength(response.cardId(), 64)
            || !hasLength(response.utterance(), 300)
            || !hasLength(response.question(), 200)
            || (response.contextQuestion() != null
                && !hasLength(response.contextQuestion(), 200))
            || response.expiresAt() == null) {
            throw invalidAiResponse();
        }
        return new FeedbackCardResponse(
            response.cardId(),
            response.contextQuestion(),
            response.utterance(),
            response.question(),
            response.expiresAt());
    }

    public FeedbackCardAnswerResponse answer(
        Long userId,
        String cardId,
        FeedbackCardAnswerRequest request) {
        AnswerResponse response = aiClient.answer(
            cardId,
            new AnswerRequest(
                userId.toString(),
                request.answer().name(),
                request.correctionText(),
                request.answeredAt(),
                request.consentVersion()));
        if (response == null || !Objects.equals(cardId, response.cardId())
            || !"ANSWERED".equals(response.status())) {
            throw invalidAiResponse();
        }
        return new FeedbackCardAnswerResponse(response.cardId(), response.status());
    }

    private boolean hasLength(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max;
    }

    private PersonaBusinessException invalidAiResponse() {
        return new PersonaBusinessException(PersonaErrorCode.AI_SERVER_RESPONSE_INVALID);
    }
}
