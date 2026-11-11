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
import tools.jackson.databind.json.JsonMapper;

class NotificationRequestOutboxCodecTest {

    private static final Instant REQUESTED_AT = Instant.parse("2026-10-30T01:02:03.123456789Z");
    private static final String VALID_JSON = """
        {"sourceEventId":"EVENT:LIKE","recipientUserId":7,"type":"LIKE",
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

    @Test
    void null_요청은_저장_JSON으로_변환하지_않는다() {
        assertThatThrownBy(() -> codec.encode(null)).isInstanceOf(NotificationInvalidCreateRequestException.class);
    }

    @Test
    void 저장된_JSON의_알_수_없는_추가_필드가_있어도_요청을_복원한다() {
        var payload = VALID_JSON.replaceFirst("\\{", "{\"legacyField\":1,");

        assertThat(codec.decode(payload)).isEqualTo(request(NotificationType.LIKE, true));
    }

    private static NotificationCreateRequested request(NotificationType type, boolean hasTarget) {
        return new NotificationCreateRequested("EVENT:" + type.name(), 7L, type,
            "좋아요😀", "하늘님이 마음을 전했어요.", hasTarget ? "PROFILE" : null, hasTarget ? 8L : null,
            REQUESTED_AT);
    }

    private static Stream<Arguments> validRequests() {
        return Arrays.stream(NotificationType.values())
            .flatMap(type -> Stream.of(Arguments.of(request(type, false)), Arguments.of(request(type, true))));
    }

    private static Stream<String> invalidPayloads() {
        return Stream.of(null, "", " ", "{", "null", "[]", "{}",
            VALID_JSON.replace("EVENT:LIKE", " "),
            VALID_JSON.replace("\"recipientUserId\":7", "\"recipientUserId\":0"),
            VALID_JSON.replace("\"type\":\"LIKE\"", "\"type\":\"UNKNOWN\""),
            VALID_JSON.replace("\"title\":\"좋아요😀\"", "\"title\":null"),
            VALID_JSON.replace("\"targetId\":8", "\"targetId\":null"),
            VALID_JSON.replace("2026-10-30T01:02:03.123456789Z", "invalid-time"));
    }
}
