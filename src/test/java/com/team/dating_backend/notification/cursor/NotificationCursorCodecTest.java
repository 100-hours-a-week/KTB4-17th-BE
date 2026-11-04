package com.team.dating_backend.notification.cursor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.dto.read.NotificationCursor;
import com.team.dating_backend.notification.enums.NotificationErrorCode;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class NotificationCursorCodecTest {

    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 25, 12, 0, 0, 123_456_000);
    private static final String TOKEN = "MXwyMDI2LTEwLTI1VDEyOjAwOjAwLjEyMzQ1NnwxMDA";
    private final NotificationCursorCodec codec = new NotificationCursorCodec();

    @ParameterizedTest
    @MethodSource("validCursors")
    void 고정된_문자열_계약으로_시각과_ID를_손실_없이_왕복한다(
        LocalDateTime createdAt, long notificationId, String token) {
        var cursor = new NotificationCursor(createdAt, notificationId);

        assertThat(codec.encode(cursor)).isEqualTo(token);
        assertThat(codec.decode(token)).isEqualTo(cursor);
    }

    @ParameterizedTest
    @MethodSource("invalidTokens")
    void 잘못된_요청은_400_오류_코드로_구분하고_파싱_원인을_보존한다(
        String token, Class<? extends Throwable> causeType) {
        assertThatThrownBy(() -> codec.decode(token))
            .isInstanceOfSatisfying(NotificationBusinessException.class, exception -> {
                assertThat(exception.getErrorCode()).isEqualTo(NotificationErrorCode.INVALID_NOTIFICATION_CURSOR);
                assertThat(exception.getMessage()).isNull();
                if (causeType == null) {
                    assertThat(exception.getCause()).isNull();
                } else {
                    assertThat(exception.getCause()).isInstanceOf(causeType);
                }
                if (exception.getCause() instanceof NotificationBusinessException modelFailure) {
                    assertThat(modelFailure.getErrorCode()).isEqualTo(CommonErrorCode.INTERNAL_SERVER_ERROR);
                }
            });
    }

    @Test
    void 같은_바이트로_해석되는_다른_Base64_표현도_정규형이_아니면_거부한다() {
        String alternate = "MXwyMDI2LTEwLTI1VDEyOjAwOjAwLjEyMzQ1NnwxMDB";
        assertThat(Base64.getUrlDecoder().decode(alternate)).isEqualTo(Base64.getUrlDecoder().decode(TOKEN));

        assertThatThrownBy(() -> codec.decode(alternate))
            .isInstanceOfSatisfying(NotificationBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(NotificationErrorCode.INVALID_NOTIFICATION_CURSOR));
    }

    @Test
    void 내부_모델은_마이크로초보다_세밀한_경계값을_잘라내지_않고_서버_오류로_거부한다() {
        assertThatThrownBy(() -> new NotificationCursor(CREATED_AT.plusNanos(1), 100L))
            .isInstanceOfSatisfying(NotificationBusinessException.class, exception -> {
                assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INTERNAL_SERVER_ERROR);
                assertThat(exception.getMessage()).isNull();
            });
    }

    @Test
    void encode의_null은_요청_오류가_아닌_서버_오류다() {
        assertThatThrownBy(() -> codec.encode(null))
            .isInstanceOfSatisfying(NotificationBusinessException.class, exception -> {
                assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INTERNAL_SERVER_ERROR);
                assertThat(exception.getMessage()).isNull();
            });
    }

    @Test
    void encode의_내부_실패는_서버_오류_코드와_원본_cause를_보존한다() {
        var cursor = mock(NotificationCursor.class);
        var failure = new IllegalStateException();
        when(cursor.createdAt()).thenThrow(failure);

        assertThatThrownBy(() -> codec.encode(cursor))
            .isInstanceOfSatisfying(NotificationBusinessException.class, exception -> {
                assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INTERNAL_SERVER_ERROR);
                assertThat(exception.getCause()).isSameAs(failure);
                assertThat(exception.getMessage()).isNull();
            });
    }

    @Test
    void decode_중_정규형_검사의_서버_오류를_400으로_바꾸지_않는다() {
        var observed = spy(codec);
        var failure = new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR,
            new IllegalStateException());
        doThrow(failure).when(observed).encode(any(NotificationCursor.class));

        assertThatThrownBy(() -> observed.decode(TOKEN)).isSameAs(failure);
    }

    private static Stream<Arguments> validCursors() {
        return Stream.of(
            Arguments.of(CREATED_AT, 100L, TOKEN),
            Arguments.of(CREATED_AT, 101L, "MXwyMDI2LTEwLTI1VDEyOjAwOjAwLjEyMzQ1NnwxMDE"),
            Arguments.of(LocalDateTime.of(2024, 2, 29, 23, 59, 59, 1_000), Long.MAX_VALUE,
                "MXwyMDI0LTAyLTI5VDIzOjU5OjU5LjAwMDAwMXw5MjIzMzcyMDM2ODU0Nzc1ODA3"),
            Arguments.of(LocalDateTime.of(1000, 1, 1, 0, 0), 1L,
                "MXwxMDAwLTAxLTAxVDAwOjAwOjAwLjAwMDAwMHwx"));
    }

    private static Stream<Arguments> invalidTokens() {
        return Stream.of(
            Arguments.of(null, null),
            Arguments.of("", null),
            Arguments.of(" ", null),
            Arguments.of("a".repeat(257), null),
            Arguments.of(TOKEN + "=", null),
            Arguments.of("A", IllegalArgumentException.class),
            Arguments.of("_w", CharacterCodingException.class),
            Arguments.of(token("2|2026-10-25T12:00:00.123456|100"), null),
            Arguments.of(token("1|2026-10-25T12:00:00.123456"), null),
            Arguments.of(token("1|2026-02-30T12:00:00.123456|100"), DateTimeParseException.class),
            Arguments.of(token("1|2026-10-25T12:00:00.1234567|100"), DateTimeParseException.class),
            Arguments.of(token("1|0999-12-31T12:00:00.123456|100"), NotificationBusinessException.class),
            Arguments.of(token("1|2026-10-25T12:00:00.123456|0"), NotificationBusinessException.class),
            Arguments.of(token("1|2026-10-25T12:00:00.123456|9223372036854775808"), NumberFormatException.class),
            Arguments.of(token("1|2026-10-25T12:00:00.123456|0100"), null));
    }

    private static String token(String payload) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }
}
