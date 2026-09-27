package com.team.dating_backend.aisimulation.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.team.dating_backend.aisimulation.config.AiSimulationProperties;
import com.team.dating_backend.aisimulation.dto.ai.AiSimulationAiPayloads;
import com.team.dating_backend.aisimulation.enums.AiSimulationErrorCode;
import com.team.dating_backend.aisimulation.exception.AiSimulationBusinessException;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

class RestAiSimulationAiClientTest {

    private MockRestServiceServer server;
    private RestAiSimulationAiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        AiSimulationProperties properties = new AiSimulationProperties();
        properties.setBaseUrl("http://ai.internal");
        client = new RestAiSimulationAiClient(
            builder.build(), properties, new ObjectMapper());
    }

    @Test
    void 시뮬레이션_요청을_snake_case와_10턴으로_보낸다() {
        server.expect(once(), requestTo("http://ai.internal/ai/api/v1/simulation"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().json("""
                {"me_user_id":"7","partner_user_id":"8","turns":10}
                """))
            .andRespond(withSuccess(successJson(), MediaType.APPLICATION_JSON));

        AiSimulationAiPayloads.SimulationResponse response = client.run(
            new AiSimulationAiPayloads.SimulationRequest("7", "8", 10));

        assertThat(response.simulationId()).isEqualTo("sim-001");
        assertThat(response.me().personaId()).isEqualTo("persona-a");
        assertThat(response.report().overall().score()).isEqualTo(78);
        server.verify();
    }

    @Test
    void 내_페르소나_없음과_상대_페르소나_없음을_구분한다() {
        server.expect(once(), requestTo("http://ai.internal/ai/api/v1/simulation"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                    {"detail":"me: 확정된 페르소나가 없어요"}
                    """));

        assertError(AiSimulationErrorCode.ME_PERSONA_NOT_FOUND);
        server.verify();
    }

    @Test
    void 상대_페르소나가_없으면_상대_오류로_변환한다() {
        server.expect(once(), requestTo("http://ai.internal/ai/api/v1/simulation"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                    {"detail":"partner: 확정된 페르소나가 없어요"}
                    """));

        assertError(AiSimulationErrorCode.TARGET_PERSONA_NOT_FOUND);
        server.verify();
    }

    @Test
    void AI_처리중과_생성실패를_백엔드_오류로_변환한다() {
        server.expect(once(), requestTo("http://ai.internal/ai/api/v1/simulation"))
            .andRespond(withStatus(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                    {"detail":"같은 조합이 이미 처리 중이에요"}
                    """));
        assertError(AiSimulationErrorCode.SIMULATION_ALREADY_RUNNING);
        server.verify();
    }

    @Test
    void AI가_요청을_거절하면_422로_변환한다() {
        server.expect(once(), requestTo("http://ai.internal/ai/api/v1/simulation"))
            .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                    {"detail":"invalid request"}
                    """));

        assertError(AiSimulationErrorCode.AI_REQUEST_REJECTED);
        server.verify();
    }

    @Test
    void AI_생성_실패는_서비스_이용불가로_변환한다() {
        server.expect(once(), requestTo("http://ai.internal/ai/api/v1/simulation"))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                    {"detail":{"message":"simulation failed","reason":"timeout"}}
                    """));

        assertError(AiSimulationErrorCode.SIMULATION_GENERATION_FAILED);
        server.verify();
    }

    @Test
    void 잘못된_JSON은_유효하지_않은_AI_응답으로_변환한다() {
        server.expect(once(), requestTo("http://ai.internal/ai/api/v1/simulation"))
            .andRespond(withSuccess("{broken", MediaType.APPLICATION_JSON));

        assertError(AiSimulationErrorCode.AI_SERVER_RESPONSE_INVALID);
        server.verify();
    }

    @Test
    void 네트워크_장애는_AI_서버_이용불가로_변환한다() {
        server.expect(once(), requestTo("http://ai.internal/ai/api/v1/simulation"))
            .andRespond(request -> {
                throw new IOException("connection timed out");
            });

        assertError(AiSimulationErrorCode.AI_SERVER_UNAVAILABLE);
        server.verify();
    }

    private void assertError(AiSimulationErrorCode expected) {
        assertThatThrownBy(() -> client.run(
            new AiSimulationAiPayloads.SimulationRequest("7", "8", 10)))
            .isInstanceOfSatisfying(
                AiSimulationBusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }

    private String successJson() {
        return """
            {
              "simulation_id":"sim-001",
              "me":{"persona_id":"persona-a","user_id":"7","nickname":"나","version":1,"accuracy":90},
              "partner":{"persona_id":"persona-b","user_id":"8","nickname":"상대","version":1,"accuracy":85},
              "turns":10,
              "transcript":[{"index":0,"speaker":"a","text":"안녕하세요"}],
              "report":{
                "simulation_id":"sim-001",
                "persona_a_id":"persona-a",
                "persona_b_id":"persona-b",
                "overall":{"score":78,"grade":"GOOD","grade_label":"잘 맞아요","headline":"편안함","summary":"좋아요"}
              },
              "created_at":"2026-09-28T01:02:03Z"
            }
            """;
    }
}
