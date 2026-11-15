package com.team.dating_backend.persona.dto.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.List;

public final class PersonaAiPayloads {

    private PersonaAiPayloads() {}

    public record StartRequest(
        String nickname,
        @JsonProperty("user_id") String userId,
        String mbti) {}

    public record UpdateNicknameRequest(
        @JsonProperty("user_id") String userId,
        String nickname) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UpdateNicknameResponse(
        @JsonProperty("user_id") String userId,
        String nickname,
        @JsonProperty("updated_sessions") Integer updatedSessions,
        @JsonProperty("updated_at") OffsetDateTime updatedAt) {}

    public record AnswerRequest(
        String answer,
        @JsonProperty("turn_index") int turnIndex) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Segment(String type, String text) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TurnResponse(
        @JsonProperty("session_id") String sessionId,
        String utterance,
        List<Segment> segments,
        String progress,
        Boolean done,
        Integer answered,
        @JsonProperty("can_skip") Boolean canSkip,
        @JsonProperty("can_finish") Boolean canFinish,
        Boolean retry,
        @JsonProperty("turn_index") Integer turnIndex) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Summary(String category, String title, String content) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Narrative(String headline, String body, List<String> traits) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PersonaResponse(
        @JsonProperty("persona_id") String personaId,
        Integer version,
        String source,
        Narrative narrative,
        List<Summary> summaries) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProfileStyle(
        String summary,
        @JsonProperty("frequent_phrases") List<String> frequentPhrases,
        List<String> endings,
        List<String> slang,
        @JsonProperty("message_length") String messageLength,
        String laughter,
        @JsonProperty("question_rate") String questionRate) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProfileResponse(
        @JsonProperty("user_id") String userId,
        @JsonProperty("persona_id") String personaId,
        Integer version,
        String nickname,
        String mbti,
        @JsonProperty("confirmed_at") OffsetDateTime confirmedAt,
        Narrative narrative,
        @JsonProperty("conversation_style") ProfileStyle conversationStyle,
        List<Summary> summaries,
        List<String> interests,
        List<String> routine,
        @JsonProperty("date_prefer") List<String> datePrefer,
        @JsonProperty("date_avoid") List<String> dateAvoid) {}

    public record ConfirmRequest(
        @JsonProperty("persona_id") String personaId,
        @JsonProperty("is_confirmed") boolean confirmed,
        String mbti,
        @JsonProperty("confirmed_at") OffsetDateTime confirmedAt) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConfirmResponse(
        @JsonProperty("persona_id") String personaId,
        @JsonProperty("user_id") String userId,
        @JsonProperty("is_confirmed") Boolean confirmed,
        String mbti,
        @JsonProperty("confirmed_at") OffsetDateTime confirmedAt) {}
}
