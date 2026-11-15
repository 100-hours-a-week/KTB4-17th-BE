package com.team.dating_backend.common.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.team.dating_backend.common.enums.CommonErrorCode;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.json.JsonMapper;

class DiscordWebhookSenderTest {

    private static final String WEBHOOK = "https://discord.com/api/webhooks/123/test-token";
    private MockRestServiceServer server;
    private DiscordWebhookSender sender;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        sender = new DiscordWebhookSender(builder.build());
    }

    @Test
    void 도메인_객체_없이_제목과_필드를_전달하고_메시지_생성을_확인한다() {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("simulationId", 7L);
        fields.put("task", "GENERATION");
        fields.put("causeType", null);
        server.expect(requestTo(WEBHOOK + "?wait=true")).andExpect(method(HttpMethod.POST))
            .andExpect(request -> {
                var payload = JsonMapper.builder().build()
                    .readTree(((MockClientHttpRequest) request).getBodyAsString());
                assertThat(payload.path("content").stringValue()).isEqualTo(
                    "@everyone AI 시뮬레이션 처리 실패\n```text\nsimulationId: 7\ntask: GENERATION\ncauseType: -\n```\n");
                assertThat(payload.path("allowed_mentions").path("parse"))
                    .isEqualTo(JsonMapper.builder().build().readTree("[\"everyone\"]"));
            }).andRespond(withSuccess("{\"id\":\"123\"}", MediaType.APPLICATION_JSON));

        sender.send(WEBHOOK, "AI 시뮬레이션 처리 실패", fields);

        assertThat(fields).containsEntry("causeType", null).hasSize(3);
        server.verify();
    }

    @Test
    void 긴_제목과_필드를_줄여도_모든_항목과_코드_블록과_유니코드를_보존한다() {
        var fields = new LinkedHashMap<String, Object>();
        String value = "`\n\u2028\u202e" + "😀".repeat(300);
        for (int i = 0; i < 30; i++) {
            fields.put("field" + i, value);
        }
        server.expect(requestTo(WEBHOOK + "?wait=true")).andExpect(request -> {
            String content = JsonMapper.builder().build()
                .readTree(((MockClientHttpRequest) request).getBodyAsString()).path("content").stringValue();
            assertThat(content).hasSizeLessThanOrEqualTo(2000).endsWith("```\n").contains("…")
                .doesNotContain("\u2028", "\u202e");
            assertThat(content.chars().filter(character -> character == '`').count()).isEqualTo(6);
            assertThat(new String(content.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)).isEqualTo(content);
            for (String name : fields.keySet()) {
                assertThat(content).contains(name + ": ");
            }
        }).andRespond(withSuccess("{\"id\":\"123\"}", MediaType.APPLICATION_JSON));

        sender.send(WEBHOOK, value, fields);

        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{}", "null", "{\"id\":123}", "{\"id\":\"\"}", "{\"id\":\"bad\"}"})
    void 생성된_메시지_ID가_없으면_에러코드로_실패한다(String body) {
        server.expect(requestTo(WEBHOOK + "?wait=true")).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> sender.send(WEBHOOK, "실패", Map.of("task", "GENERATION")))
            .isInstanceOfSatisfying(DiscordWebhookException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INTERNAL_SERVER_ERROR));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {204, 302})
    void 성공_확인_응답이_아니면_메시지_ID가_있어도_실패한다(int status) {
        server.expect(requestTo(WEBHOOK + "?wait=true")).andRespond(withStatus(HttpStatus.valueOf(status))
            .contentType(MediaType.APPLICATION_JSON).body("{\"id\":\"123\"}"));

        assertThatThrownBy(() -> sender.send(WEBHOOK, "실패", Map.of("task", "GENERATION")))
            .isInstanceOf(DiscordWebhookException.class);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500})
    void HTTP_오류의_상태를_보존하고_재전송하지_않는다(int status) {
        server.expect(requestTo(WEBHOOK + "?wait=true")).andRespond(withStatus(HttpStatus.valueOf(status)));

        assertThatThrownBy(() -> sender.send(WEBHOOK, "실패", Map.of("task", "GENERATION")))
            .isInstanceOfSatisfying(RestClientResponseException.class,
                exception -> assertThat(exception.getStatusCode().value()).isEqualTo(status));
        server.verify();
    }

    @Test
    void 통신_시간_초과의_원인을_보존하고_재전송하지_않는다() {
        var timeout = new SocketTimeoutException();
        server.expect(requestTo(WEBHOOK + "?wait=true")).andRespond(withException(timeout));

        assertThatThrownBy(() -> sender.send(WEBHOOK, "실패", Map.of("task", "GENERATION")))
            .isInstanceOf(ResourceAccessException.class).hasCause(timeout);
        server.verify();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
        strings = {"https://example.invalid/api/webhooks/123/token", "http://discord.com/api/webhooks/123/token",
            "https://discord.com:444/api/webhooks/123/token", "https://user@discord.com/api/webhooks/123/token",
            "https://discord.com/api/webhooks/123/token?wait=true",
            "https://discord.com/api/webhooks/123/token#fragment",
            "https://discord.com/other/123/token", "not a URI"}
    )
    void 잘못된_웹훅은_URL을_예외에_포함하지_않고_HTTP_요청_전에_거부한다(String url) {
        assertThatThrownBy(() -> sender.send(url, "실패", Map.of("task", "GENERATION")))
            .isInstanceOf(DiscordWebhookException.class).hasMessage(null);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    void 표현할_수_없는_메시지는_HTTP_요청_전에_거부한다(int scenario) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put(scenario == 4 ? "bad\nname" : "task", "GENERATION");
        if (scenario == 3) {
            fields.clear();
        } else if (scenario == 5) {
            for (int i = 0; i < 30; i++) {
                fields.put("f".repeat(62) + i, "value");
            }
        }
        String title = scenario == 0 ? null : scenario == 1 ? " " : "실패";

        assertThatThrownBy(() -> sender.send(WEBHOOK, title, scenario == 2 ? null : fields))
            .isInstanceOf(DiscordWebhookException.class);
        server.verify();
    }
}
