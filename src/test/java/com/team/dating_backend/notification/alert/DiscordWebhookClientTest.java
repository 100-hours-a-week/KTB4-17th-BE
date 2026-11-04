package com.team.dating_backend.notification.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.team.dating_backend.notification.config.NotificationAlertProperties;
import com.team.dating_backend.notification.alert.NotificationFailureContext.KafkaFailureDetails;
import com.team.dating_backend.notification.alert.NotificationFailureContext.KafkaLocation;
import com.team.dating_backend.notification.enums.NotificationFailureCode;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import java.time.Instant;
import java.time.LocalDateTime;
import com.team.dating_backend.notification.dto.event.NotificationDeliveryEvent;
import com.team.dating_backend.notification.dto.response.NotificationEventResponse;
import com.team.dating_backend.notification.alert.NotificationFailureContext.DeliveryFailureDetails;
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
    void DLT_위치와_시도를_표시하고_미확인_값은_구분한다() {
        server.expect(requestTo(WEBHOOK + "?wait=true"))
            .andExpect(request -> {
                String content = JsonMapper.builder().build()
                    .readTree(((MockClientHttpRequest) request).getBodyAsString())
                    .path("content").stringValue();
                assertThat(content).contains("failureStage: CREATION_PROCESSING", "consumerGroup: retry-group",
                    "originalTopic: create", "originalPartition: 0", "originalOffset: 7",
                    "inputTopic: create-retry-1", "inputOffset: 11", "destinationTopic: create-dlt",
                    "destinationOffset: 99", "attempt: 3", "retryable: true",
                    "processingExceptionType: TimeoutException",
                    "processingCauseType: -");
            }).andRespond(withSuccess("{\"id\":\"123\"}", MediaType.APPLICATION_JSON));
        var details = new KafkaFailureDetails("retry-group", new KafkaLocation("create", 0, 7L),
            new KafkaLocation("create-retry-1", 0, 11L), new KafkaLocation("create-dlt", 0, 99L),
            3, true, "TimeoutException", null);
        client.send(
            NotificationFailureContext.from(NotificationFailureCode.DLT_RECORDED, null, null, Instant.now(), details));
        server.verify();
    }

    @Test
    void 모든_Kafka_필드가_길어도_항목과_코드_블록과_문자_경계를_유지한다() {
        String longValue = "`\n\u2028\u202e" + "😀".repeat(300);
        server.expect(requestTo(WEBHOOK + "?wait=true"))
            .andExpect(request -> {
                String content = JsonMapper.builder().build()
                    .readTree(((MockClientHttpRequest) request).getBodyAsString())
                    .path("content").stringValue();
                assertThat(content.length()).isLessThanOrEqualTo(2000);
                assertThat(content).endsWith("```\n").contains("…", "originalTopic: ", "inputTopic: ",
                    "destinationTopic: ",
                    "destinationOffset: 9223372036854775807", "attempt: 32767", "retryable: false",
                    "processingCauseType: ", "errorCode: DELIVERY_INVALID_EVENT",
                    "deliverySchemaVersion: 2147483647", "notificationId: 9223372036854775807",
                    "notificationCreatedAt: ");
                assertThat(content.chars().filter(character -> character == '`').count()).isEqualTo(6);
                assertThat(content).doesNotContain("\u2028", "\u202e");
                for (int i = 0; i < content.length(); i++) {
                    char value = content.charAt(i);
                    if (Character.isHighSurrogate(value)) {
                        assertThat(i + 1).isLessThan(content.length());
                        assertThat(Character.isLowSurrogate(content.charAt(++i))).isTrue();
                    } else {
                        assertThat(Character.isLowSurrogate(value)).isFalse();
                    }
                }
            }).andRespond(withSuccess("{\"id\":\"123\"}", MediaType.APPLICATION_JSON));
        properties.setEnvironment(longValue);
        var location = new KafkaLocation(longValue, Integer.MAX_VALUE, Long.MAX_VALUE);
        var details = new KafkaFailureDetails(longValue, location, location, location, 32767, false, longValue,
            longValue);
        client.send(
            new NotificationFailureContext(NotificationFailureCode.DELIVERY_INVALID_EVENT, longValue, Long.MAX_VALUE,
                NotificationType.LIKE, null, Instant.now(), longValue, longValue, details,
                new DeliveryFailureDetails(Integer.MAX_VALUE, Long.MAX_VALUE, LocalDateTime.MAX)));
        server.verify();
    }

    @Test
    void delivery_식별자와_저장_시각을_표시하고_요청_시각을_추정하거나_내용을_노출하지_않는다() {
        server.expect(requestTo(WEBHOOK + "?wait=true"))
            .andExpect(request -> {
                String content = JsonMapper.builder().build()
                    .readTree(((MockClientHttpRequest) request).getBodyAsString())
                    .path("content").stringValue();
                assertThat(content).contains("errorCode: DELIVERY_INVALID_EVENT", "failureStage: DELIVERY_CONSUMPTION",
                    "deliverySchemaVersion: 99", "notificationId: 31", "notificationCreatedAt: 2026-10-23T12:00",
                    "requestedAt: -", "type: -", "inputTopic: delivery", "inputOffset: 18")
                    .doesNotContain("private-title", "private-body");
            }).andRespond(withSuccess("{\"id\":\"123\"}", MediaType.APPLICATION_JSON));
        var snapshot = new NotificationEventResponse(31L, "UNKNOWN", "private-title", "private-body", null, null, null,
            LocalDateTime.of(2026, 10, 23, 12, 0));
        var details = new KafkaFailureDetails("process-group", null, new KafkaLocation("delivery", 2, 18L), null,
            null, false, "NotificationInvalidDeliveryEventException", null);
        client.send(NotificationFailureContext.fromDelivery(NotificationFailureCode.DELIVERY_INVALID_EVENT,
            new NotificationDeliveryEvent(99, "source", 7L, snapshot), null, Instant.now(), details));
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
