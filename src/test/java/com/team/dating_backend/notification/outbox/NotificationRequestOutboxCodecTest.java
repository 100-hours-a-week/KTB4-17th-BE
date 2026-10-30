package com.team.dating_backend.notification.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.notification.exception.NotificationInvalidCreateRequestException;
import com.team.dating_backend.notification.service.NotificationCreateRequestedValidator;
import java.time.Instant;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class NotificationRequestOutboxCodecTest {

    private static final Instant REQUESTED_AT = Instant.parse("2026-10-30T01:02:03.123456789Z");
    private static final String VALID_JSON = """
        {"schemaVersion":1,"sourceEventId":"EVENT:LIKE","recipientUserId":7,"type":"LIKE",
         "title":"좋아요😀","body":"하늘님이 마음을 전했어요.","targetType":"PROFILE","targetId":8,
         "requestedAt":"2026-10-30T01:02:03.123456789Z"}
        """;
    private final NotificationRequestOutboxCodec codec = new NotificationRequestOutboxCodec(
        new NotificationCreateRequestedValidator());

    @Test
    void 기존_메시지의_필드명과_문자열_시각_계약으로_변환하고_복원한다() {
        var request = request(NotificationType.LIKE, true);
        var json = JsonMapper.builder().build();

        assertThat(json.readTree(codec.encode(request))).isEqualTo(json.readTree(VALID_JSON));
        assertThat(codec.decode(VALID_JSON)).isEqualTo(request);
    }

    @ParameterizedTest
    @MethodSource("validRequests")
    void 모든_알림_유형과_대상의_null_여부_및_나노초_요청시각을_보존한다(NotificationCreateRequested request) {
        assertThat(codec.decode(codec.encode(request))).isEqualTo(request);
    }

    @ParameterizedTest
    @MethodSource("invalidPayloads")
    void JSON_문법이_잘못됐거나_요청_계약이_유효하지_않으면_복원을_거부한다(String payload) {
        assertThatThrownBy(() -> codec.decode(payload)).isInstanceOf(NotificationInvalidCreateRequestException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void 지원하지_않는_버전의_요청은_저장_JSON으로_변환하지_않는다(int schemaVersion) {
        var request = new NotificationCreateRequested(schemaVersion, "EVENT:LIKE", 7L, NotificationType.LIKE,
            "좋아요", "본문", null, null, REQUESTED_AT);

        assertThatThrownBy(() -> codec.encode(request)).isInstanceOf(NotificationInvalidCreateRequestException.class);
    }

    @Test
    void null_요청은_저장_JSON으로_변환하지_않는다() {
        assertThatThrownBy(() -> codec.encode(null)).isInstanceOf(NotificationInvalidCreateRequestException.class);
    }

    private static NotificationCreateRequested request(NotificationType type, boolean hasTarget) {
        return new NotificationCreateRequested(1, "EVENT:" + type.name(), 7L, type,
            "좋아요😀", "하늘님이 마음을 전했어요.", hasTarget ? "PROFILE" : null, hasTarget ? 8L : null,
            REQUESTED_AT);
    }

    private static Stream<Arguments> validRequests() {
        return Arrays.stream(NotificationType.values())
            .flatMap(type -> Stream.of(Arguments.of(request(type, false)), Arguments.of(request(type, true))));
    }

    private static Stream<String> invalidPayloads() {
        return Stream.of(null, "", " ", "{", "null", "[]", "{}",
            VALID_JSON.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            VALID_JSON.replace("EVENT:LIKE", " "),
            VALID_JSON.replace("\"recipientUserId\":7", "\"recipientUserId\":0"),
            VALID_JSON.replace("\"type\":\"LIKE\"", "\"type\":\"UNKNOWN\""),
            VALID_JSON.replace("\"title\":\"좋아요😀\"", "\"title\":null"),
            VALID_JSON.replace("\"targetId\":8", "\"targetId\":null"),
            VALID_JSON.replace("2026-10-30T01:02:03.123456789Z", "invalid-time"));
    }
}
