package com.team.dating_backend.notification.service;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.config.NotificationSseProperties;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.sse.NotificationEmitterRegistry;
import com.team.dating_backend.notification.sse.NotificationSseConnection;
import com.team.dating_backend.notification.sse.NotificationSseLifecycle;
import com.team.dating_backend.notification.sse.NotificationSseSender;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.util.DisconnectedClientHelper;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationSubscriptionService {

    private final NotificationAccessService notificationAccessService;
    private final NotificationEmitterRegistry registry;
    private final NotificationSseSender sender;
    private final NotificationSseProperties properties;
    private final NotificationSseLifecycle lifecycle;

    public NotificationSseConnection subscribe(Long userId) throws IOException {
        notificationAccessService.assertActiveUser(userId);
        lifecycle.assertAcceptingSubscriptions();

        NotificationSseConnection connection = new NotificationSseConnection(
            userId,
            UUID.randomUUID().toString(),
            new SseEmitter(properties.getTimeoutMs()),
            LocalDateTime.now());

        try {
            registerCallbacks(connection);

            connection.lockForSend();
            try {
                if (!lifecycle.registerIfAccepting(connection)) {
                    throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
                }

                sender.sendConnected(connection);
            } finally {
                connection.unlockForSend();
            }

            log.info("SSE subscription prepared. userId={}, connectionId={}",
                connection.getUserId(), connection.getConnectionId());
            return connection;
        } catch (IOException | RuntimeException exception) {
            registry.remove(connection);
            throw exception;
        }
    }

    private void registerCallbacks(NotificationSseConnection connection) {
        SseEmitter emitter = connection.getEmitter();
        emitter.onTimeout(() -> handleTimeout(connection));
        emitter.onError(exception -> handleError(connection, exception));
        emitter.onCompletion(() -> handleCompletion(connection));
    }

    private void handleTimeout(NotificationSseConnection connection) {
        registry.remove(connection);
        log.debug("SSE subscription timed out. userId={}, connectionId={}",
            connection.getUserId(), connection.getConnectionId());

        try {
            connection.getEmitter().complete();
        } catch (RuntimeException exception) {
            if (DisconnectedClientHelper.isClientDisconnectedException(exception)) {
                log.debug("SSE timeout completion failed after disconnect. userId={}, connectionId={}",
                    connection.getUserId(), connection.getConnectionId());
                return;
            }

            log.error("SSE timeout completion failed. errorCode={}, userId={}, connectionId={}, exceptionType={}",
                CommonErrorCode.INTERNAL_SERVER_ERROR, connection.getUserId(), connection.getConnectionId(),
                exception.getClass().getSimpleName());
        }
    }

    private void handleError(NotificationSseConnection connection, Throwable exception) {
        registry.remove(connection);

        if (exception instanceof IOException
            || DisconnectedClientHelper.isClientDisconnectedException(exception)) {
            log.debug("SSE subscription transport error. userId={}, connectionId={}, exceptionType={}",
                connection.getUserId(), connection.getConnectionId(), exception.getClass().getSimpleName());
            return;
        }

        log.error("SSE subscription error. errorCode={}, userId={}, connectionId={}, exceptionType={}",
            CommonErrorCode.INTERNAL_SERVER_ERROR, connection.getUserId(), connection.getConnectionId(),
            exception.getClass().getSimpleName());
    }

    private void handleCompletion(NotificationSseConnection connection) {
        registry.remove(connection);
        log.info("SSE subscription completed. userId={}, connectionId={}",
            connection.getUserId(), connection.getConnectionId());
    }
}
