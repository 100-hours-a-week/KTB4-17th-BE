package com.team.dating_backend.notification.sse;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class NotificationEmitterRegistry {

    private final ConcurrentHashMap<Long, ConcurrentHashMap<String, NotificationSseConnection>> connections = new ConcurrentHashMap<>();

    public boolean register(NotificationSseConnection connection) {
        requireConnection(connection);
        boolean[] registered = {false};

        connections.compute(connection.getUserId(), (userId, userConnections) -> {
            if (connection.isClosed()) {
                return userConnections;
            }

            ConcurrentHashMap<String, NotificationSseConnection> current = userConnections == null
                ? new ConcurrentHashMap<>()
                : userConnections;
            NotificationSseConnection existing = current.putIfAbsent(
                connection.getConnectionId(), connection);
            if (existing != null && existing != connection) {
                throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
            }

            registered[0] = true;
            return current;
        });

        return registered[0];
    }

    public List<NotificationSseConnection> findByUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }

        ConcurrentHashMap<String, NotificationSseConnection> userConnections = connections.get(userId);
        if (userConnections == null) {
            return List.of();
        }

        return userConnections.values().stream()
            .filter(connection -> !connection.isClosed())
            .toList();
    }

    public List<NotificationSseConnection> snapshotAll() {
        return connections.values().stream()
            .flatMap(userConnections -> userConnections.values().stream())
            .filter(connection -> !connection.isClosed())
            .toList();
    }

    public void remove(NotificationSseConnection connection) {
        requireConnection(connection);
        connection.markClosed();

        connections.computeIfPresent(connection.getUserId(), (userId, userConnections) -> {
            userConnections.remove(connection.getConnectionId(), connection);
            return userConnections.isEmpty() ? null : userConnections;
        });
    }

    private void requireConnection(NotificationSseConnection connection) {
        if (connection == null) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
    }
}
