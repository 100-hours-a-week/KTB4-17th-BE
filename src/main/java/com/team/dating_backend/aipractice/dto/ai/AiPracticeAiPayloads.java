package com.team.dating_backend.aipractice.dto.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class AiPracticeAiPayloads {

    private AiPracticeAiPayloads() {}

    public record StartSessionRequest(
        @JsonProperty("partner_user_id") String partnerUserId,
        @JsonProperty("me_user_id") String meUserId,
        String nickname) {}

    public record MessageRequest(String message) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StartSessionResponse(
        @JsonProperty("session_id") String sessionId) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GenerationReplyResponse(
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("message_index") Integer messageIndex,
        String content,
        String source) {}
}
