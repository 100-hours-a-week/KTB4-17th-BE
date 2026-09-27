package com.team.dating_backend.persona.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.team.dating_backend.persona.config.PersonaAiProperties;
import com.team.dating_backend.persona.dto.ai.PersonaAiPayloads;
import com.team.dating_backend.persona.enums.PersonaErrorCode;
import com.team.dating_backend.persona.exception.PersonaBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

class RestPersonaAiClientTest {

    private MockRestServiceServer server;
    private RestPersonaAiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        PersonaAiProperties properties = new PersonaAiProperties();
        properties.setBaseUrl("http://ai.internal");
        client = new RestPersonaAiClient(builder, properties, new ObjectMapper());
    }

    @Test
    void 시작_요청은_user_id와_MBTI를_보낸다() {
        server.expect(once(), requestTo("http://ai.internal/ai/api/v1/persona/onboarding/start"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().json("""
                {"nickname":"하루","user_id":"7","mbti":"ENFJ"}
                """))
            .andRespond(withSuccess(turnJson(), MediaType.APPLICATION_JSON));

        PersonaAiPayloads.TurnResponse response = client.start(
            new PersonaAiPayloads.StartRequest("하루", "7", "ENFJ"));

        assertThat(response.sessionId()).isEqualTo("session-1");
        assertThat(response.turnIndex()).isZero();
        server.verify();
    }

    @Test
    void 답변_요청은_turn_index를_포함한다() {
        server.expect(
            once(),
            requestTo("http://ai.internal/ai/api/v1/persona/onboarding/session-1/answer"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().json("""
                {"answer":"주말에는 산책해요","turn_index":0}
                """))
            .andRespond(withSuccess(turnJson(), MediaType.APPLICATION_JSON));

        client.answer(
            "session-1",
            new PersonaAiPayloads.AnswerRequest("주말에는 산책해요", 0));

        server.verify();
    }

    @Test
    void AI의_request_in_progress는_백엔드_오류로_변환한다() {
        server.expect(
            once(),
            requestTo("http://ai.internal/ai/api/v1/persona/onboarding/session-1/skip"))
            .andRespond(withStatus(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                    {"detail":{"code":"request_in_progress","message":"처리 중"}}
                    """));

        assertThatThrownBy(() -> client.skip("session-1"))
            .isInstanceOfSatisfying(
                PersonaBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(PersonaErrorCode.REQUEST_IN_PROGRESS));
        server.verify();
    }

    @Test
    void build_응답의_narrative와_동적_summaries를_역직렬화한다() {
        server.expect(
            once(),
            requestTo("http://ai.internal/ai/api/v1/persona/session-1/build"))
            .andRespond(withSuccess("""
                {
                  "persona_id":"persona-1",
                  "version":1,
                  "source":"llm",
                  "narrative":{
                    "headline":"천천히 깊어지는 사람",
                    "body":"각자의 시간을 존중하는 편이에요.",
                    "traits":["혼자만의 시간이 중요해요","대화로 풀어요"]
                  },
                  "summaries":[
                    {"category":"orientation","title":"제목1","content":"내용1"},
                    {"category":"intimacy","title":"제목2","content":"내용2"}
                  ],
                  "scores":{"avoidance":50}
                }
                """, MediaType.APPLICATION_JSON));

        PersonaAiPayloads.PersonaResponse response = client.build("session-1");

        assertThat(response.source()).isEqualTo("llm");
        assertThat(response.narrative().headline()).isEqualTo("천천히 깊어지는 사람");
        assertThat(response.narrative().traits()).containsExactly("혼자만의 시간이 중요해요", "대화로 풀어요");
        assertThat(response.summaries())
            .extracting("category")
            .containsExactly("orientation", "intimacy");
        server.verify();
    }

    @Test
    void fallback_build의_null_narrative와_빈_summaries를_역직렬화한다() {
        server.expect(
            once(),
            requestTo("http://ai.internal/ai/api/v1/persona/session-1/build"))
            .andRespond(withSuccess("""
                {
                  "persona_id":"persona-1",
                  "version":1,
                  "source":"fallback",
                  "narrative":null,
                  "summaries":[],
                  "scores":{"avoidance":50}
                }
                """, MediaType.APPLICATION_JSON));

        PersonaAiPayloads.PersonaResponse response = client.build("session-1");

        assertThat(response.source()).isEqualTo("fallback");
        assertThat(response.narrative()).isNull();
        assertThat(response.summaries()).isEmpty();
        server.verify();
    }

    private String turnJson() {
        return """
            {
              "session_id":"session-1",
              "utterance":"첫 질문",
              "segments":[{"type":"message","text":"첫 질문"}],
              "choices":["선택지"],
              "progress":"1/10",
              "done":false,
              "answered":0,
              "can_skip":false,
              "can_finish":false,
              "retry":false,
              "turn_index":0
            }
            """;
    }
}
