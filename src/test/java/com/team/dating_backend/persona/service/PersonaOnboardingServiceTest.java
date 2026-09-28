package com.team.dating_backend.persona.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.team.dating_backend.persona.client.PersonaAiClient;
import com.team.dating_backend.persona.dto.ai.PersonaAiPayloads;
import com.team.dating_backend.persona.dto.request.PersonaAnswerRequest;
import com.team.dating_backend.persona.dto.response.PersonaConversationResponse;
import com.team.dating_backend.persona.dto.response.PersonaDraftResponse;
import com.team.dating_backend.persona.enums.PersonaErrorCode;
import com.team.dating_backend.persona.exception.PersonaBusinessException;
import com.team.dating_backend.onboarding.service.OnboardingCompletionService;
import com.team.dating_backend.profile.entity.Profile;
import com.team.dating_backend.profile.enums.Mbti;
import com.team.dating_backend.profile.repository.ProfileRepository;
import com.team.dating_backend.user.entity.User;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class PersonaOnboardingServiceTest {

    private static final Long USER_ID = 7L;
    private static final String SESSION_ID = "session-1";

    private ProfileRepository profileRepository;
    private PersonaAiClient aiClient;
    private OnboardingCompletionService onboardingCompletionService;
    private Profile profile;
    private User user;
    private PersonaOnboardingService service;

    @BeforeEach
    void setUp() {
        profileRepository = Mockito.mock(ProfileRepository.class);
        aiClient = Mockito.mock(PersonaAiClient.class);
        onboardingCompletionService = Mockito.mock(OnboardingCompletionService.class);
        profile = Mockito.mock(Profile.class);
        user = Mockito.mock(User.class);
        service = new PersonaOnboardingService(
            profileRepository, aiClient, onboardingCompletionService);

        given(profileRepository.findByUserIdAndDeletedAtIsNull(USER_ID))
            .willReturn(Optional.of(profile));
        given(profile.getNickname()).willReturn("하루");
        given(profile.getUser()).willReturn(user);
    }

    @Test
    void 시작은_프로필_닉네임과_사용자_ID와_MBTI를_AI에_전달한다() {
        given(profile.getMbti()).willReturn(Mbti.ENFJ);
        given(aiClient.start(any())).willReturn(turn(SESSION_ID, false, false, 0));

        PersonaConversationResponse result = service.start(USER_ID);

        ArgumentCaptor<PersonaAiPayloads.StartRequest> captor = ArgumentCaptor.forClass(
            PersonaAiPayloads.StartRequest.class);
        verify(aiClient).start(captor.capture());
        assertThat(captor.getValue().nickname()).isEqualTo("하루");
        assertThat(captor.getValue().userId()).isEqualTo("7");
        assertThat(captor.getValue().mbti()).isEqualTo("ENFJ");
        assertThat(result.sessionId()).isEqualTo(SESSION_ID);
        assertThat(result.personaDraft()).isNull();
    }

    @Test
    void 다시_시작하면_AI_시작_API를_다시_호출한다() {
        given(aiClient.start(any()))
            .willReturn(turn("session-1", false, false, 0))
            .willReturn(turn("session-2", false, false, 0));

        PersonaConversationResponse first = service.start(USER_ID);
        PersonaConversationResponse second = service.start(USER_ID);

        assertThat(first.sessionId()).isNotEqualTo(second.sessionId());
        verify(aiClient, times(2)).start(any());
    }

    @Test
    void 재질문_응답은_같은_턴_번호와_retry를_그대로_반환한다() {
        given(aiClient.answer(any(), any()))
            .willReturn(turn(SESSION_ID, false, true, 3));

        PersonaConversationResponse result = service.answer(
            USER_ID,
            SESSION_ID,
            new PersonaAnswerRequest("다시 답할게요", 3));

        assertThat(result.retry()).isTrue();
        assertThat(result.turnIndex()).isEqualTo(3);
        assertThat(result.personaDraft()).isNull();
        verify(aiClient, never()).build(any());
    }

    @Test
    void 마지막_답변이면_자동으로_build한다() {
        given(aiClient.answer(any(), any()))
            .willReturn(turn(SESSION_ID, true, false, 10));
        given(aiClient.build(SESSION_ID)).willReturn(draft());

        PersonaConversationResponse result = service.answer(
            USER_ID,
            SESSION_ID,
            new PersonaAnswerRequest("마지막 답변", 9));

        assertThat(result.done()).isTrue();
        assertThat(result.personaDraft()).isNotNull();
        assertThat(result.personaDraft().summaries())
            .extracting("category")
            .containsExactly("intimacy", "communication");
        assertThat(result.personaDraft().narrative().headline()).isEqualTo("천천히 깊어지는 사람");
        verify(aiClient).build(SESSION_ID);
    }

    @Test
    void 마지막_질문_건너뛰기도_자동으로_build한다() {
        given(aiClient.skip(SESSION_ID)).willReturn(turn(SESSION_ID, true, false, 10));
        given(aiClient.build(SESSION_ID)).willReturn(draft());

        PersonaConversationResponse result = service.skip(USER_ID, SESSION_ID);

        assertThat(result.done()).isTrue();
        assertThat(result.personaDraft()).isNotNull();
        verify(aiClient).build(SESSION_ID);
    }

    @Test
    void 조기종료는_종료_후_자동으로_build한다() {
        given(aiClient.finish(SESSION_ID)).willReturn(turn(SESSION_ID, true, false, 4));
        given(aiClient.build(SESSION_ID)).willReturn(draft());

        PersonaConversationResponse result = service.finish(USER_ID, SESSION_ID);

        assertThat(result.done()).isTrue();
        assertThat(result.personaDraft()).isNotNull();
        verify(aiClient).finish(SESSION_ID);
        verify(aiClient).build(SESSION_ID);
    }

    @Test
    void 일부_결과_카드는_AI가_반환한_순서를_유지한다() {
        PersonaAiPayloads.PersonaResponse response = personaResponse(
            "llm",
            narrative(),
            List.of(summary("orientation"), summary("intimacy")));
        given(aiClient.build(SESSION_ID)).willReturn(response);

        PersonaDraftResponse result = service.build(USER_ID, SESSION_ID);

        assertThat(result.summaries())
            .extracting("category")
            .containsExactly("orientation", "intimacy");
        assertThat(result.narrative().traits()).containsExactly("혼자만의 시간도 중요해요", "대화로 풀어요");
    }

    @Test
    void 다섯_영역의_결과_카드를_모두_허용한다() {
        PersonaAiPayloads.PersonaResponse response = personaResponse(
            "llm",
            narrative(),
            List.of(
                summary("intimacy"),
                summary("communication"),
                summary("conflict"),
                summary("ideal"),
                summary("orientation")));
        given(aiClient.build(SESSION_ID)).willReturn(response);

        PersonaDraftResponse result = service.build(USER_ID, SESSION_ID);

        assertThat(result.summaries()).hasSize(5);
    }

    @Test
    void fallback은_narrative와_결과_카드가_없어도_허용한다() {
        PersonaAiPayloads.PersonaResponse response = personaResponse(
            "fallback",
            null,
            List.of());
        given(aiClient.build(SESSION_ID)).willReturn(response);

        PersonaDraftResponse result = service.build(USER_ID, SESSION_ID);

        assertThat(result.source()).isEqualTo("fallback");
        assertThat(result.narrative()).isNull();
        assertThat(result.summaries()).isEmpty();
    }

    @Test
    void 알_수_없는_결과_카테고리는_잘못된_AI_응답이다() {
        PersonaAiPayloads.PersonaResponse invalid = personaResponse(
            "llm",
            narrative(),
            List.of(summary("unknown")));

        assertInvalidDraft(invalid);
    }

    @Test
    void 중복된_결과_카테고리는_잘못된_AI_응답이다() {
        PersonaAiPayloads.PersonaResponse invalid = personaResponse(
            "llm",
            narrative(),
            List.of(summary("intimacy"), summary("intimacy")));

        assertInvalidDraft(invalid);
    }

    @Test
    void 내용이_빈_결과_카드는_잘못된_AI_응답이다() {
        PersonaAiPayloads.PersonaResponse invalid = personaResponse(
            "llm",
            narrative(),
            List.of(new PersonaAiPayloads.Summary("intimacy", "제목", " ")));

        assertInvalidDraft(invalid);
    }

    @Test
    void headline이_빈_narrative는_잘못된_AI_응답이다() {
        PersonaAiPayloads.PersonaResponse invalid = personaResponse(
            "llm",
            new PersonaAiPayloads.Narrative(" ", "본문", List.of("특징")),
            List.of());

        assertInvalidDraft(invalid);
    }

    private void assertInvalidDraft(PersonaAiPayloads.PersonaResponse invalid) {
        given(aiClient.build(SESSION_ID)).willReturn(invalid);

        assertThatThrownBy(() -> service.build(USER_ID, SESSION_ID))
            .isInstanceOfSatisfying(
                PersonaBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(PersonaErrorCode.AI_SERVER_RESPONSE_INVALID));
    }

    @Test
    void 확정은_프로필_MBTI와_UTC_시각을_AI에_전달한다() {
        given(profile.getMbti()).willReturn(Mbti.ENFJ);
        OffsetDateTime aiConfirmedAt = OffsetDateTime.of(
            2026, 9, 27, 10, 0, 0, 0, ZoneOffset.UTC);
        given(aiClient.confirm(any(), any())).willReturn(
            new PersonaAiPayloads.ConfirmResponse(
                "persona-1", "7", true, "ENFJ", aiConfirmedAt));

        service.confirm(USER_ID, "persona-1");

        ArgumentCaptor<PersonaAiPayloads.ConfirmRequest> captor = ArgumentCaptor.forClass(
            PersonaAiPayloads.ConfirmRequest.class);
        verify(aiClient).confirm(org.mockito.ArgumentMatchers.eq("persona-1"), captor.capture());
        assertThat(captor.getValue().mbti()).isEqualTo("ENFJ");
        assertThat(captor.getValue().confirmedAt().getOffset()).isEqualTo(ZoneOffset.UTC);
        verify(user).confirmPersonaOnboarding(any(LocalDateTime.class));
        verify(onboardingCompletionService).activateIfCompleted(USER_ID);
    }

    @Test
    void AI_확정_응답이_유효하지_않으면_CONFIRMED로_변경하지_않는다() {
        given(profile.getMbti()).willReturn(Mbti.ENFJ);
        given(aiClient.confirm(any(), any())).willReturn(
            new PersonaAiPayloads.ConfirmResponse(
                "different-persona", "7", true, "ENFJ", OffsetDateTime.now()));

        assertThatThrownBy(() -> service.confirm(USER_ID, "persona-1"))
            .isInstanceOfSatisfying(
                PersonaBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(PersonaErrorCode.AI_SERVER_RESPONSE_INVALID));
        verify(user, never()).confirmPersonaOnboarding(any(LocalDateTime.class));
        verify(onboardingCompletionService, never()).activateIfCompleted(any());
    }

    @Test
    void 프로필_MBTI가_없으면_AI를_호출하지_않는다() {
        given(profile.getMbti()).willReturn(null);

        assertThatThrownBy(() -> service.confirm(USER_ID, "persona-1"))
            .isInstanceOfSatisfying(
                PersonaBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(PersonaErrorCode.PROFILE_MBTI_REQUIRED));
        verify(aiClient, never()).confirm(any(), any());
    }

    private PersonaAiPayloads.TurnResponse turn(
        String sessionId,
        boolean done,
        boolean retry,
        int turnIndex) {
        return new PersonaAiPayloads.TurnResponse(
            sessionId,
            done ? "완료" : "다음 질문",
            List.of(new PersonaAiPayloads.Segment(done ? "closing" : "message", "발화")),
            done ? "10/10" : "1/10",
            done,
            done ? 10 : 0,
            !done,
            !done,
            retry,
            turnIndex);
    }

    private PersonaAiPayloads.PersonaResponse draft() {
        return personaResponse(
            "llm",
            narrative(),
            List.of(summary("intimacy"), summary("communication")));
    }

    private PersonaAiPayloads.PersonaResponse personaResponse(
        String source,
        PersonaAiPayloads.Narrative narrative,
        List<PersonaAiPayloads.Summary> summaries) {
        return new PersonaAiPayloads.PersonaResponse(
            "persona-1",
            1,
            source,
            narrative,
            summaries);
    }

    private PersonaAiPayloads.Narrative narrative() {
        return new PersonaAiPayloads.Narrative(
            "천천히 깊어지는 사람",
            "각자의 시간을 존중하면서 솔직한 대화를 중요하게 생각하는 편이에요.",
            List.of("혼자만의 시간도 중요해요", "대화로 풀어요"));
    }

    private PersonaAiPayloads.Summary summary(String category) {
        return new PersonaAiPayloads.Summary(category, category + " 제목", category + " 내용");
    }
}
