package com.team.dating_backend.notification.dto.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.team.dating_backend.notification.dto.response.NotificationEventResponse;
import com.team.dating_backend.notification.enums.NotificationType;
import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import tools.jackson.databind.ObjectMapper;

@JsonTest
class NotificationMessageJsonTest {

    private static final Instant REQUESTED_AT = Instant.parse("2026-10-20T01:00:00.123456789Z");
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 20, 10, 0, 0, 123456000);
    private static final LocalDateTime READ_AT = LocalDateTime.of(2026, 10, 20, 10, 1, 0, 654321000);

    private static final String CREATE_REQUEST_JSON = """
        {
          "schemaVersion": 1,
          "sourceEventId": "CHAT_MESSAGE_SENT:900",
          "recipientUserId": 42,
          "type": "CHAT_MESSAGE",
          "title": "새 메시지",
          "body": "새 메시지가 도착했습니다.",
          "targetType": "CHAT_ROOM",
          "targetId": 7,
          "requestedAt": "2026-10-20T01:00:00.123456789Z"
        }
        """;

    private static final String DELIVERY_EVENT_JSON = """
        {
          "schemaVersion": 1,
          "sourceEventId": "CHAT_MESSAGE_SENT:900",
          "recipientUserId": 42,
          "notification": {
            "notificationId": 100,
            "type": "CHAT_MESSAGE",
            "title": "새 메시지",
            "body": "새 메시지가 도착했습니다.",
            "targetType": "CHAT_ROOM",
            "targetId": 7,
            "readAt": "2026-10-20T10:01:00.654321",
            "createdAt": "2026-10-20T10:00:00.123456"
          }
        }
        """;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void 생성_요청을_계약된_필드와_UTC_시각으로_serialize한다() {
        String json = objectMapper.writeValueAsString(createRequest());

        assertThat(objectMapper.readTree(json))
            .isEqualTo(objectMapper.readTree(CREATE_REQUEST_JSON));
    }

    @Test
    void 생성_요청_JSON의_원본_식별자와_나노초_정밀도를_deserialize한다() {
        NotificationCreateRequested request = objectMapper.readValue(
            CREATE_REQUEST_JSON, NotificationCreateRequested.class);

        assertThat(request).isEqualTo(createRequest());
    }

    @Test
    void 대상이_없는_생성_요청은_null_필드를_포함하고_복원한다() {
        NotificationCreateRequested request = new NotificationCreateRequested(
            NotificationCreateRequested.CURRENT_SCHEMA_VERSION,
            "NOTICE_CREATED:901", 42L, NotificationType.NOTICE,
            "공지", "서비스 공지입니다.", null, null, REQUESTED_AT);

        String json = objectMapper.writeValueAsString(request);

        assertThat(objectMapper.readTree(json)).isEqualTo(objectMapper.readTree("""
            {
              "schemaVersion": 1,
              "sourceEventId": "NOTICE_CREATED:901",
              "recipientUserId": 42,
              "type": "NOTICE",
              "title": "공지",
              "body": "서비스 공지입니다.",
              "targetType": null,
              "targetId": null,
              "requestedAt": "2026-10-20T01:00:00.123456789Z"
            }
            """));
        assertThat(objectMapper.readValue(json, NotificationCreateRequested.class)).isEqualTo(request);
    }

    @Test
    void delivery_이벤트의_내부_정보와_알림_응답을_구분하여_serialize한다() {
        String json = objectMapper.writeValueAsString(deliveryEvent());

        assertThat(objectMapper.readTree(json))
            .isEqualTo(objectMapper.readTree(DELIVERY_EVENT_JSON));
    }

    @Test
    void delivery_JSON의_알림_ID와_마이크로초_정밀도를_deserialize한다() {
        NotificationDeliveryEvent event = objectMapper.readValue(
            DELIVERY_EVENT_JSON, NotificationDeliveryEvent.class);

        assertThat(event).isEqualTo(deliveryEvent());
    }

    @Test
    void delivery_이벤트는_대상과_읽은_시각의_null을_포함하고_복원한다() {
        NotificationEventResponse notification = new NotificationEventResponse(
            101L, "NOTICE", "공지", "서비스 공지입니다.", null, null, null, CREATED_AT);
        NotificationDeliveryEvent event = new NotificationDeliveryEvent(
            NotificationDeliveryEvent.CURRENT_SCHEMA_VERSION, "NOTICE_CREATED:901", 42L, notification);

        String json = objectMapper.writeValueAsString(event);

        assertThat(objectMapper.readTree(json)).isEqualTo(objectMapper.readTree("""
            {
              "schemaVersion": 1,
              "sourceEventId": "NOTICE_CREATED:901",
              "recipientUserId": 42,
              "notification": {
                "notificationId": 101,
                "type": "NOTICE",
                "title": "공지",
                "body": "서비스 공지입니다.",
                "targetType": null,
                "targetId": null,
                "readAt": null,
                "createdAt": "2026-10-20T10:00:00.123456"
              }
            }
            """));
        assertThat(objectMapper.readValue(json, NotificationDeliveryEvent.class)).isEqualTo(event);
    }

    private NotificationCreateRequested createRequest() {
        return new NotificationCreateRequested(
            NotificationCreateRequested.CURRENT_SCHEMA_VERSION,
            "CHAT_MESSAGE_SENT:900", 42L, NotificationType.CHAT_MESSAGE,
            "새 메시지", "새 메시지가 도착했습니다.", "CHAT_ROOM", 7L, REQUESTED_AT);
    }

    private NotificationDeliveryEvent deliveryEvent() {
        NotificationEventResponse notification = new NotificationEventResponse(
            100L, "CHAT_MESSAGE", "새 메시지", "새 메시지가 도착했습니다.",
            "CHAT_ROOM", 7L, READ_AT, CREATED_AT);
        return new NotificationDeliveryEvent(
            NotificationDeliveryEvent.CURRENT_SCHEMA_VERSION, "CHAT_MESSAGE_SENT:900", 42L, notification);
    }
}
