package com.team.dating_backend.persona.dto.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;

public final class FeedbackCardAiPayloads {

    private FeedbackCardAiPayloads() {}

    public record NextRequest(@JsonProperty("user_id") String userId) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CardResponse(
        @JsonProperty("card_id") String cardId,
        @JsonProperty("context_question") String contextQuestion,
        String utterance,
        String question,
        @JsonProperty("expires_at") OffsetDateTime expiresAt) {}

    public record AnswerRequest(
        @JsonProperty("user_id") String userId,
        String answer,
        @JsonProperty("correction_text") String correctionText,
        @JsonProperty("answered_at") OffsetDateTime answeredAt,
        @JsonProperty("consent_version") String consentVersion) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AnswerResponse(
        @JsonProperty("card_id") String cardId,
        String status) {}
}
