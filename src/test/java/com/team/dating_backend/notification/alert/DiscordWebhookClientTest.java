package com.team.dating_backend.notification.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.team.dating_backend.notification.config.NotificationAlertProperties;
import com.team.dating_backend.notification.enums.NotificationFailureCode;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.json.JsonMapper;

class DiscordWebhookClientTest {

    private static final String WEBHOOK = "https://discord.com/api/webhooks/123/test-token";

    private final NotificationAlertProperties properties = new NotificationAlertProperties();
    private MockRestServiceServer server;
    private DiscordWebhookClient client;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        properties.setEnabled(true);
        properties.setEnvironment("dev");
        properties.setWebhookUrl(WEBHOOK);
        client = new DiscordWebhookClient(builder.build(), properties);
    }

    @Test
    void everyone_멘션과_실패_정보를_전송하고_메시지_생성을_확인한다() {
        server.expect(requestTo(WEBHOOK + "?wait=true"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(request -> {
                var payload = JsonMapper.builder().build()
                    .readTree(((MockClientHttpRequest) request).getBodyAsString());
                assertThat(payload.path("content").stringValue())
                    .startsWith("@everyone 알림 처리 실패\n")
                    .contains("environment: dev", "errorCode: PUBLISH_RESULT_TIMEOUT", "sourceEventId: event-123",
                        "recipientUserId: 7", "type: LIKE", "requestedAt: 2026-10-20T01:00:00Z",
                        "occurredAt: 2026-10-20T01:01:00Z", "exceptionType: TimeoutException")
                    .doesNotContain("test-token", "https://");
                assertThat(payload.path("allowed_mentions").path("parse"))
                    .isEqualTo(JsonMapper.builder().build().readTree("[\"everyone\"]"));
            })
            .andRespond(withSuccess("{\"id\":\"123456789\"}", MediaType.APPLICATION_JSON));

        client.send(context("event-123"));

        server.verify();
    }

    @Test
    void 긴_식별자와_제어_문자가_메시지_길이와_코드_블록을_깨뜨리지_않는다() {
        server.expect(requestTo(WEBHOOK + "?wait=true"))
            .andExpect(request -> {
                var payload = JsonMapper.builder().build()
                    .readTree(((MockClientHttpRequest) request).getBodyAsString());
                String content = payload.path("content").stringValue();
                assertThat(content.length()).isLessThanOrEqualTo(2000);
                assertThat(content.chars().filter(character -> character == '`').count()).isEqualTo(6);
                String source = content.lines().filter(line -> line.startsWith("sourceEventId: "))
                    .findFirst().orElseThrow().substring("sourceEventId: ".length());
                assertThat(source.codePointCount(0, source.length())).isEqualTo(128);
            })
            .andRespond(withSuccess("{\"id\":\"123\"}", MediaType.APPLICATION_JSON));

        client.send(context("bad\n`" + "😀".repeat(1000)));

        server.verify();
    }

    @Test
    void 비활성화하면_HTTP_요청을_보내지_않는다() {
        properties.setEnabled(false);

        client.send(context("event-123"));

        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 204, 302})
    void 생성된_메시지의_확인이_없으면_성공으로_처리하지_않는다(int status) {
        server.expect(requestTo(WEBHOOK + "?wait=true"))
            .andRespond(withStatus(HttpStatus.valueOf(status)).contentType(MediaType.APPLICATION_JSON).body("{}"));

        assertThatThrownBy(() -> client.send(context("event-123")))
            .isInstanceOf(NotificationBusinessException.class);

        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500})
    void HTTP_실패의_상태를_유지하고_자동_재전송하지_않는다(int status) {
        server.expect(requestTo(WEBHOOK + "?wait=true"))
            .andRespond(withStatus(HttpStatus.valueOf(status)));

        assertThatThrownBy(() -> client.send(context("event-123")))
            .isInstanceOfSatisfying(RestClientResponseException.class,
                exception -> assertThat(exception.getStatusCode().value()).isEqualTo(status));

        server.verify();
    }

    private NotificationFailureContext context(String sourceEventId) {
        return new NotificationFailureContext(NotificationFailureCode.PUBLISH_RESULT_TIMEOUT,
            sourceEventId, 7L, NotificationType.LIKE, Instant.parse("2026-10-20T01:00:00Z"),
            Instant.parse("2026-10-20T01:01:00Z"), "TimeoutException", null);
    }
}
