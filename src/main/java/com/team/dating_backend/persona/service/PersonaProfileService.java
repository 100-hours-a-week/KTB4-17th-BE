package com.team.dating_backend.persona.service;

import com.team.dating_backend.persona.client.PersonaAiClient;
import com.team.dating_backend.persona.dto.ai.PersonaAiPayloads;
import com.team.dating_backend.persona.dto.response.PersonaConversationStyleResponse;
import com.team.dating_backend.persona.dto.response.PersonaNarrativeResponse;
import com.team.dating_backend.persona.dto.response.PersonaProfileResponse;
import com.team.dating_backend.persona.dto.response.PersonaSummaryResponse;
import com.team.dating_backend.persona.enums.PersonaErrorCode;
import com.team.dating_backend.persona.exception.PersonaBusinessException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PersonaProfileService {

    private static final Set<String> SUMMARY_CATEGORIES = Set.of(
        "intimacy",
        "communication",
        "conflict",
        "ideal",
        "orientation");
    private static final Set<String> MBTI_TYPES = Set.of(
        "ISTJ", "ISFJ", "INFJ", "INTJ",
        "ISTP", "ISFP", "INFP", "INTP",
        "ESTP", "ESFP", "ENFP", "ENTP",
        "ESTJ", "ESFJ", "ENFJ", "ENTJ");
    private static final int MAX_NARRATIVE_HEADLINE_LENGTH = 60;
    private static final int MAX_NARRATIVE_BODY_LENGTH = 800;
    private static final int MAX_NARRATIVE_TRAITS = 6;
    private static final int MAX_SUMMARY_TITLE_LENGTH = 40;
    private static final int MAX_SUMMARY_CONTENT_LENGTH = 200;

    private final PersonaAiClient aiClient;

    public PersonaProfileResponse getMyProfile(Long userId) {
        String expectedUserId = userId.toString();
        PersonaAiPayloads.ProfileResponse response = aiClient.getProfile(expectedUserId);
        validate(response, expectedUserId);
        return toResponse(response);
    }

    private PersonaProfileResponse toResponse(PersonaAiPayloads.ProfileResponse response) {
        PersonaNarrativeResponse narrative = response.narrative() == null
            ? null
            : new PersonaNarrativeResponse(
                response.narrative().headline(),
                response.narrative().body(),
                response.narrative().traits());
        PersonaConversationStyleResponse style = response.conversationStyle() == null
            ? null
            : new PersonaConversationStyleResponse(
                response.conversationStyle().summary(),
                response.conversationStyle().frequentPhrases(),
                response.conversationStyle().endings(),
                response.conversationStyle().slang(),
                response.conversationStyle().messageLength(),
                response.conversationStyle().laughter(),
                response.conversationStyle().questionRate());
        List<PersonaSummaryResponse> summaries = response.summaries().stream()
            .map(summary -> new PersonaSummaryResponse(
                summary.category(), summary.title(), summary.content()))
            .toList();
        return new PersonaProfileResponse(
            response.userId(),
            response.personaId(),
            response.version(),
            response.nickname(),
            response.mbti(),
            response.confirmedAt(),
            narrative,
            style,
            summaries,
            response.interests(),
            response.routine(),
            response.datePrefer(),
            response.dateAvoid());
    }

    private void validate(PersonaAiPayloads.ProfileResponse response, String expectedUserId) {
        if (response == null
            || !expectedUserId.equals(response.userId())
            || isBlank(response.personaId())
            || response.version() == null
            || response.version() < 1
            || isBlank(response.nickname())
            || response.mbti() != null && !MBTI_TYPES.contains(response.mbti())
            || !validNarrative(response.narrative())
            || !validSummaries(response.summaries())
            || !validTexts(response.interests())
            || !validTexts(response.routine())
            || !validTexts(response.datePrefer())
            || !validTexts(response.dateAvoid())
            || !validStyle(response.conversationStyle())) {
            throw invalidResponse();
        }
    }

    private boolean validNarrative(PersonaAiPayloads.Narrative narrative) {
        if (narrative == null) {
            return true;
        }
        return !isBlank(narrative.headline())
            && narrative.headline().length() <= MAX_NARRATIVE_HEADLINE_LENGTH
            && !isBlank(narrative.body())
            && narrative.body().length() <= MAX_NARRATIVE_BODY_LENGTH
            && validTexts(narrative.traits())
            && narrative.traits().size() <= MAX_NARRATIVE_TRAITS;
    }

    private boolean validSummaries(List<PersonaAiPayloads.Summary> summaries) {
        if (summaries == null || summaries.size() > SUMMARY_CATEGORIES.size()) {
            return false;
        }
        Set<String> categories = new HashSet<>();
        return summaries.stream().allMatch(summary -> summary != null
            && SUMMARY_CATEGORIES.contains(summary.category())
            && categories.add(summary.category())
            && !isBlank(summary.title())
            && summary.title().length() <= MAX_SUMMARY_TITLE_LENGTH
            && !isBlank(summary.content())
            && summary.content().length() <= MAX_SUMMARY_CONTENT_LENGTH);
    }

    private boolean validStyle(PersonaAiPayloads.ProfileStyle style) {
        return style == null
            || validOptionalText(style.summary())
                && validTexts(style.frequentPhrases())
                && validTexts(style.endings())
                && validTexts(style.slang())
                && validOptionalText(style.messageLength())
                && validOptionalText(style.laughter())
                && validOptionalText(style.questionRate());
    }

    private boolean validTexts(List<String> values) {
        return values != null && values.stream().noneMatch(this::isBlank);
    }

    private boolean validOptionalText(String value) {
        return value == null || !value.isBlank();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private PersonaBusinessException invalidResponse() {
        return new PersonaBusinessException(PersonaErrorCode.AI_SERVER_RESPONSE_INVALID);
    }
}
