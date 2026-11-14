package com.team.dating_backend.persona.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.team.dating_backend.persona.client.PersonaAiClient;
import com.team.dating_backend.persona.dto.ai.PersonaAiPayloads;
import com.team.dating_backend.persona.dto.response.PersonaProfileResponse;
import com.team.dating_backend.persona.enums.PersonaErrorCode;
import com.team.dating_backend.persona.exception.PersonaBusinessException;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PersonaProfileServiceTest {

    private PersonaAiClient aiClient;
    private PersonaProfileService service;

    @BeforeEach
    void setUp() {
        aiClient = Mockito.mock(PersonaAiClient.class);
        service = new PersonaProfileService(aiClient);
    }

    @Test
    void AI_응답을_내_페르소나_조회_결과로_변환한다() {
        given(aiClient.getProfile("7")).willReturn(profile("7"));

        PersonaProfileResponse response = service.getMyProfile(7L);

        assertThat(response.userId()).isEqualTo("7");
        assertThat(response.personaId()).isEqualTo("persona-1");
        assertThat(response.narrative().headline()).isEqualTo("천천히 깊어지는 사람");
        assertThat(response.conversationStyle().frequentPhrases()).containsExactly("오 진짜요?");
        assertThat(response.summaries())
            .extracting("category")
            .containsExactly("orientation", "intimacy");
        assertThat(response.datePrefer()).containsExactly("조용한 전시");
    }

    @Test
    void AI가_다른_사용자의_페르소나를_반환하면_거부한다() {
        given(aiClient.getProfile("7")).willReturn(profile("8"));

        assertThatThrownBy(() -> service.getMyProfile(7L))
            .isInstanceOfSatisfying(
                PersonaBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(PersonaErrorCode.AI_SERVER_RESPONSE_INVALID));
    }

    private PersonaAiPayloads.ProfileResponse profile(String userId) {
        return new PersonaAiPayloads.ProfileResponse(
            userId,
            "persona-1",
            3,
            "하루",
            "INFJ",
            OffsetDateTime.parse("2026-11-10T12:34:56Z"),
            new PersonaAiPayloads.Narrative(
                "천천히 깊어지는 사람",
                "각자의 시간을 존중해요.",
                List.of("신중함", "다정함")),
            new PersonaAiPayloads.ProfileStyle(
                "짧고 다정하게 반응해요.",
                List.of("오 진짜요?"),
                List.of("~요ㅎㅎ"),
                List.of("ㄹㅇ"),
                "짧게 여러 번 보내요",
                "ㅎㅎ를 자주 써요",
                "꼬리 질문을 자주 해요"),
            List.of(
                new PersonaAiPayloads.Summary("orientation", "오래 함께할 사람", "진지한 관계를 바라요."),
                new PersonaAiPayloads.Summary("intimacy", "천천히 가까워져요", "시간을 두고 알아가요.")),
            List.of("산책"),
            List.of("퇴근 후 러닝"),
            List.of("조용한 전시"),
            List.of("시끄러운 술집"));
    }
}
