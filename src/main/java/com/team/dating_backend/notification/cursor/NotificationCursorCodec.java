package com.team.dating_backend.notification.cursor;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.dto.read.NotificationCursor;
import com.team.dating_backend.notification.enums.NotificationErrorCode;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class NotificationCursorCodec {

    private static final String FORMAT_VERSION = "1";
    private static final int MAX_CURSOR_LENGTH = 256;
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_-]+");
    private static final DateTimeFormatter CREATED_AT_FORMATTER = DateTimeFormatter
        .ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS", Locale.ROOT)
        .withResolverStyle(ResolverStyle.STRICT);

    public NotificationCursor decode(String token) {
        if (token == null || token.isBlank() || token.length() > MAX_CURSOR_LENGTH
            || !TOKEN_PATTERN.matcher(token).matches()) {
            throw invalidCursor(null);
        }

        String[] parts = decodePayload(token).split("\\|", -1);
        if (parts.length != 3 || !FORMAT_VERSION.equals(parts[0])) {
            throw invalidCursor(null);
        }

        NotificationCursor cursor;
        try {
            cursor = new NotificationCursor(
                LocalDateTime.parse(parts[1], CREATED_AT_FORMATTER), Long.parseLong(parts[2]));
        } catch (DateTimeException | IllegalArgumentException | NotificationBusinessException exception) {
            throw invalidCursor(exception);
        }

        if (!token.equals(encode(cursor))) {
            throw invalidCursor(null);
        }
        return cursor;
    }

    public String encode(NotificationCursor cursor) {
        if (cursor == null) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }

        try {
            String payload = FORMAT_VERSION + "|" + cursor.createdAt().format(CREATED_AT_FORMATTER)
                + "|" + cursor.notificationId();
            return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException exception) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR, exception);
        }
    }

    private String decodePayload(String token) {
        try {
            byte[] payload = Base64.getUrlDecoder().decode(token);
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(payload)).toString();
        } catch (IllegalArgumentException | CharacterCodingException exception) {
            throw invalidCursor(exception);
        }
    }

    private NotificationBusinessException invalidCursor(Throwable cause) {
        var exception = new NotificationBusinessException(NotificationErrorCode.INVALID_NOTIFICATION_CURSOR);
        exception.initCause(cause);
        return exception;
    }
}
