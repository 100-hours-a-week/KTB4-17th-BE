package com.team.dating_backend.notification.sse;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.dto.response.NotificationConnectedResponse;
import com.team.dating_backend.notification.dto.response.NotificationEventResponse;
import com.team.dating_backend.notification.enums.NotificationSseCloseReason;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.sse.NotificationSseObservation.EventKind;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.util.DisconnectedClientHelper;

@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationSseSender {

    private static final String CONNECTED_EVENT = "connected";
    private static final String NOTIFICATION_EVENT = "notification";
    private static final String HEARTBEAT_COMMENT = "heartbeat";

    private final NotificationEmitterRegistry registry;
    private final NotificationSseObservation observation;

    public void sendConnected(NotificationSseConnection connection) throws IOException {
        requireConnection(connection);
        SseEmitter.SseEventBuilder event = SseEmitter.event()
            .name(CONNECTED_EVENT)
            .data(new NotificationConnectedResponse(connection.getConnectionId()),
                MediaType.APPLICATION_JSON);

        boolean sent;
        try {
            sent = send(connection, event);
        } catch (IOException | RuntimeException exception) {
            if (!isCompletionRace(connection, exception)) {
                observation.sendFailed(connection, EventKind.CONNECTED, failureReason(exception), exception);
            }
            throw exception;
        }
        if (!sent) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    public void sendHeartbeat(NotificationSseConnection connection) {
        requireConnection(connection);
        sendSafely(connection, SseEmitter.event().comment(HEARTBEAT_COMMENT), EventKind.HEARTBEAT);
    }

    public void sendNotification(Long userId, NotificationEventResponse response) {
        if (response == null) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }

        for (NotificationSseConnection connection : registry.findByUserId(userId)) {
            SseEmitter.SseEventBuilder event = SseEmitter.event()
                .name(NOTIFICATION_EVENT)
                .data(response, MediaType.APPLICATION_JSON);
            sendSafely(connection, event, EventKind.NOTIFICATION);
        }
    }

    private boolean send(NotificationSseConnection connection, SseEmitter.SseEventBuilder event)
        throws IOException {
        connection.lockForSend();
        try {
            if (connection.isClosed()) {
                return false;
            }

            connection.getEmitter().send(event);
            return true;
        } finally {
            connection.unlockForSend();
        }
    }

    private void sendSafely(
        NotificationSseConnection connection, SseEmitter.SseEventBuilder event, EventKind eventKind) {
        try {
            send(connection, event);
        } catch (IOException | RuntimeException exception) {
            handleSendFailure(connection, eventKind, exception);
        }
    }

    private void handleSendFailure(
        NotificationSseConnection connection, EventKind eventKind, Exception exception) {
        if (isCompletionRace(connection, exception)) {
            registry.remove(connection, NotificationSseCloseReason.COMPLETION);
            log.debug("SSE send skipped after completion. userId={}, connectionId={}, event={}",
                connection.getUserId(), connection.getConnectionId(), eventKind);
            return;
        }

        NotificationSseCloseReason reason = failureReason(exception);
        registry.remove(connection, reason);
        observation.sendFailed(connection, eventKind, reason, exception);
        if (reason == NotificationSseCloseReason.TRANSPORT_ERROR) {
            return;
        }

        NotificationBusinessException failure = new NotificationBusinessException(
            CommonErrorCode.INTERNAL_SERVER_ERROR);
        failure.initCause(exception);
        try {
            connection.getEmitter().completeWithError(failure);
        } catch (RuntimeException completionFailure) {
            if (DisconnectedClientHelper.isClientDisconnectedException(completionFailure)) {
                log.debug("SSE error completion failed after disconnect. userId={}, connectionId={}",
                    connection.getUserId(), connection.getConnectionId());
                return;
            }

            log.error("SSE error completion failed. errorCode={}, userId={}, connectionId={}, exceptionType={}",
                CommonErrorCode.INTERNAL_SERVER_ERROR, connection.getUserId(), connection.getConnectionId(),
                completionFailure.getClass().getSimpleName());
        }
    }

    private boolean isCompletionRace(NotificationSseConnection connection, Exception exception) {
        return connection.isClosed() && exception instanceof IllegalStateException && exception.getCause() == null
            && !DisconnectedClientHelper.isClientDisconnectedException(exception);
    }

    private NotificationSseCloseReason failureReason(Exception exception) {
        return exception instanceof IOException
            || DisconnectedClientHelper.isClientDisconnectedException(exception)
                ? NotificationSseCloseReason.TRANSPORT_ERROR
                : NotificationSseCloseReason.SERVER_ERROR;
    }

    private void requireConnection(NotificationSseConnection connection) {
        if (connection == null) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
    }
}
