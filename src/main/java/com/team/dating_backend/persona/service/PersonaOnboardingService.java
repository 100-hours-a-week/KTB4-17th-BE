package com.team.dating_backend.persona.service;

import com.team.dating_backend.persona.client.PersonaAiClient;
import com.team.dating_backend.persona.dto.ai.PersonaAiPayloads;
import com.team.dating_backend.persona.dto.request.PersonaAnswerRequest;
import com.team.dating_backend.persona.dto.response.PersonaConfirmationResponse;
import com.team.dating_backend.persona.dto.response.PersonaConversationResponse;
import com.team.dating_backend.persona.dto.response.PersonaDraftResponse;
import com.team.dating_backend.persona.dto.response.PersonaNarrativeResponse;
import com.team.dating_backend.persona.dto.response.PersonaSegmentResponse;
import com.team.dating_backend.persona.dto.response.PersonaSummaryResponse;
import com.team.dating_backend.persona.enums.PersonaErrorCode;
import com.team.dating_backend.persona.exception.PersonaBusinessException;
import com.team.dating_backend.onboarding.service.OnboardingCompletionService;
import com.team.dating_backend.profile.entity.Profile;
import com.team.dating_backend.profile.repository.ProfileRepository;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PersonaOnboardingService {

    private static final Set<String> SUMMARY_CATEGORIES = Set.of(
        "intimacy",
        "communication",
        "conflict",
        "ideal",
        "orientation");
    private static final Set<String> PERSONA_SOURCES = Set.of("llm", "fallback");
    private static final int MAX_NARRATIVE_HEADLINE_LENGTH = 60;
    private static final int MAX_NARRATIVE_BODY_LENGTH = 800;
    private static final int MAX_NARRATIVE_TRAITS = 6;
    private static final int MAX_SUMMARY_TITLE_LENGTH = 40;
    private static final int MAX_SUMMARY_CONTENT_LENGTH = 200;

    private final ProfileRepository profileRepository;
    private final PersonaAiClient aiClient;
    private final OnboardingCompletionService onboardingCompletionService;

    @Transactional
    public PersonaConversationResponse start(Long userId) {
        Profile profile = requireProfile(userId);
        String nickname = profile.getNickname();
        if (nickname == null || nickname.isBlank()) {
            throw new PersonaBusinessException(PersonaErrorCode.PROFILE_NICKNAME_REQUIRED);
        }

        PersonaAiPayloads.TurnResponse response = aiClient.start(
            new PersonaAiPayloads.StartRequest(
                nickname,
                userId.toString(),
                profile.getMbti() == null ? null : profile.getMbti().name()));
        PersonaConversationResponse conversation = toConversation(response, null, true);
        profile.getUser().startPersonaOnboarding(LocalDateTime.now());
        return conversation;
    }

    public PersonaConversationResponse answer(
        Long userId,
        String sessionId,
        PersonaAnswerRequest request) {
        requireProfile(userId);
        PersonaAiPayloads.TurnResponse response = aiClient.answer(
            sessionId,
            new PersonaAiPayloads.AnswerRequest(request.answer(), request.turnIndex()));
        return toConversation(response, sessionId, true);
    }

    public PersonaConversationResponse skip(Long userId, String sessionId) {
        requireProfile(userId);
        PersonaAiPayloads.TurnResponse response = aiClient.skip(sessionId);
        return toConversation(response, sessionId, true);
    }

    public PersonaConversationResponse finish(Long userId, String sessionId) {
        requireProfile(userId);
        PersonaAiPayloads.TurnResponse response = aiClient.finish(sessionId);
        validateTurn(response, sessionId);
        if (!Boolean.TRUE.equals(response.done())) {
            throw invalidResponse();
        }
        return toConversation(response, sessionId, true);
    }

    public PersonaDraftResponse build(Long userId, String sessionId) {
        requireProfile(userId);
        return build(sessionId);
    }

    @Transactional
    public PersonaConfirmationResponse confirm(Long userId, String personaId) {
        Profile profile = requireProfile(userId);
        if (profile.getMbti() == null) {
            throw new PersonaBusinessException(PersonaErrorCode.PROFILE_MBTI_REQUIRED);
        }

        OffsetDateTime confirmedAt = OffsetDateTime.now(ZoneOffset.UTC);
        PersonaAiPayloads.ConfirmResponse response = aiClient.confirm(
            personaId,
            new PersonaAiPayloads.ConfirmRequest(
                personaId,
                true,
                profile.getMbti().name(),
                confirmedAt));
        validateConfirmation(response, personaId, userId);
        profile.getUser().confirmPersonaOnboarding(LocalDateTime.now());
        onboardingCompletionService.activateIfCompleted(userId);
        return new PersonaConfirmationResponse(
            response.personaId(),
            true,
            response.mbti(),
            response.confirmedAt());
    }

    private PersonaConversationResponse toConversation(
        PersonaAiPayloads.TurnResponse response,
        String expectedSessionId,
        boolean buildWhenDone) {
        validateTurn(response, expectedSessionId);
        boolean done = Boolean.TRUE.equals(response.done());
        PersonaDraftResponse draft = done && buildWhenDone
            ? build(response.sessionId())
            : null;
        List<PersonaSegmentResponse> segments = response.segments().stream()
            .map(segment -> new PersonaSegmentResponse(segment.type(), segment.text()))
            .toList();
        return new PersonaConversationResponse(
            response.sessionId(),
            response.utterance(),
            segments,
            response.progress(),
            done,
            response.answered() == null ? 0 : response.answered(),
            Boolean.TRUE.equals(response.canSkip()),
            Boolean.TRUE.equals(response.canFinish()),
            Boolean.TRUE.equals(response.retry()),
            response.turnIndex(),
            draft);
    }

    private PersonaDraftResponse build(String sessionId) {
        PersonaAiPayloads.PersonaResponse response = aiClient.build(sessionId);
        validateDraft(response);
        PersonaNarrativeResponse narrative = response.narrative() == null
            ? null
            : new PersonaNarrativeResponse(
                response.narrative().headline(),
                response.narrative().body(),
                response.narrative().traits());
        List<PersonaSummaryResponse> summaries = response.summaries().stream()
            .map(summary -> new PersonaSummaryResponse(
                summary.category(), summary.title(), summary.content()))
            .toList();
        return new PersonaDraftResponse(
            response.personaId(),
            response.version(),
            response.source(),
            narrative,
            summaries);
    }

    private Profile requireProfile(Long userId) {
        return profileRepository.findByUserIdAndDeletedAtIsNull(userId)
            .orElseThrow(() -> new PersonaBusinessException(PersonaErrorCode.PROFILE_NOT_FOUND));
    }

    private void validateTurn(
        PersonaAiPayloads.TurnResponse response,
        String expectedSessionId) {
        if (response == null
            || isBlank(response.sessionId())
            || expectedSessionId != null && !expectedSessionId.equals(response.sessionId())
            || isBlank(response.utterance())
            || isBlank(response.progress())
            || response.segments() == null
            || response.segments().isEmpty()
            || response.segments().stream()
                .anyMatch(segment -> segment == null
                    || isBlank(segment.type())
                    || isBlank(segment.text()))
            || response.turnIndex() == null
            || response.turnIndex() < 0) {
            throw invalidResponse();
        }
    }

    private void validateDraft(PersonaAiPayloads.PersonaResponse response) {
        if (response == null
            || isBlank(response.personaId())
            || response.version() == null
            || response.version() < 1
            || isBlank(response.source())
            || !PERSONA_SOURCES.contains(response.source())
            || response.summaries() == null
            || response.summaries().size() > SUMMARY_CATEGORIES.size()
            || !isValidNarrative(response.narrative())) {
            throw invalidResponse();
        }

        Set<String> categories = new HashSet<>();
        for (PersonaAiPayloads.Summary summary : response.summaries()) {
            if (summary == null
                || isBlank(summary.category())
                || !SUMMARY_CATEGORIES.contains(summary.category())
                || !categories.add(summary.category())
                || isBlank(summary.title())
                || summary.title().length() > MAX_SUMMARY_TITLE_LENGTH
                || isBlank(summary.content())
                || summary.content().length() > MAX_SUMMARY_CONTENT_LENGTH) {
                throw invalidResponse();
            }
        }
    }

    private boolean isValidNarrative(PersonaAiPayloads.Narrative narrative) {
        if (narrative == null) {
            return true;
        }
        return !isBlank(narrative.headline())
            && narrative.headline().length() <= MAX_NARRATIVE_HEADLINE_LENGTH
            && !isBlank(narrative.body())
            && narrative.body().length() <= MAX_NARRATIVE_BODY_LENGTH
            && narrative.traits() != null
            && narrative.traits().size() <= MAX_NARRATIVE_TRAITS
            && narrative.traits().stream().noneMatch(this::isBlank);
    }

    private void validateConfirmation(
        PersonaAiPayloads.ConfirmResponse response,
        String personaId,
        Long userId) {
        if (response == null
            || !personaId.equals(response.personaId())
            || !userId.toString().equals(response.userId())
            || !Boolean.TRUE.equals(response.confirmed())
            || isBlank(response.mbti())
            || response.confirmedAt() == null) {
            throw invalidResponse();
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private PersonaBusinessException invalidResponse() {
        return new PersonaBusinessException(PersonaErrorCode.AI_SERVER_RESPONSE_INVALID);
    }
}
