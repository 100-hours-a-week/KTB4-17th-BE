package com.team.dating_backend.notification.sse;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.enums.NotificationSseCloseReason;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NotificationEmitterRegistry {

    private final NotificationSseObservation observation;
    private final ConcurrentHashMap<Long, ConcurrentHashMap<String, RegisteredConnection>> connections = new ConcurrentHashMap<>();

    public boolean register(NotificationSseConnection connection) {
        requireConnection(connection);
        boolean[] registered = {false};

        connections.compute(connection.getUserId(), (userId, userConnections) -> {
            if (connection.isClosed()) {
                return userConnections;
            }

            ConcurrentHashMap<String, RegisteredConnection> current = userConnections == null
                ? new ConcurrentHashMap<>()
                : userConnections;
            RegisteredConnection existing = current.get(connection.getConnectionId());
            if (existing != null && existing.connection() != connection) {
                throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
            }
            if (existing == null) {
                current.put(connection.getConnectionId(),
                    new RegisteredConnection(connection, System.nanoTime(), new AtomicBoolean()));
            }

            registered[0] = true;
            return current;
        });

        return registered[0];
    }

    public void observeRegistration(NotificationSseConnection connection) {
        requireConnection(connection);
        ConcurrentHashMap<String, RegisteredConnection> userConnections = connections.get(connection.getUserId());
        RegisteredConnection registered = userConnections == null
            ? null
            : userConnections.get(connection.getConnectionId());
        if (registered != null && registered.connection() == connection) {
            observeRegistration(registered);
        }
    }

    public List<NotificationSseConnection> findByUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }

        ConcurrentHashMap<String, RegisteredConnection> userConnections = connections.get(userId);
        if (userConnections == null) {
            return List.of();
        }

        return userConnections.values().stream()
            .map(RegisteredConnection::connection)
            .filter(connection -> !connection.isClosed())
            .toList();
    }

    public List<NotificationSseConnection> snapshotAll() {
        return connections.values().stream()
            .flatMap(userConnections -> userConnections.values().stream())
            .map(RegisteredConnection::connection)
            .filter(connection -> !connection.isClosed())
            .toList();
    }

    public void remove(NotificationSseConnection connection) {
        remove(connection, NotificationSseCloseReason.COMPLETION);
    }

    public boolean remove(NotificationSseConnection connection, NotificationSseCloseReason reason) {
        requireConnection(connection);
        if (reason == null) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
        connection.markClosed();
        RegisteredConnection[] removed = {null};
        long[] lifetimeNanos = {0};

        connections.computeIfPresent(connection.getUserId(), (userId, userConnections) -> {
            RegisteredConnection registered = userConnections.get(connection.getConnectionId());
            if (registered != null && registered.connection() == connection
                && userConnections.remove(connection.getConnectionId(), registered)) {
                removed[0] = registered;
                lifetimeNanos[0] = System.nanoTime() - registered.registeredAtNanos();
            }
            return userConnections.isEmpty() ? null : userConnections;
        });

        if (removed[0] == null) {
            return false;
        }
        observeRegistration(removed[0]);
        observation.connectionClosed(connection, reason, lifetimeNanos[0]);
        return true;
    }

    private void observeRegistration(RegisteredConnection registered) {
        if (registered.registrationObserved().compareAndSet(false, true)) {
            observation.connectionRegistered(registered.connection());
        }
    }

    private void requireConnection(NotificationSseConnection connection) {
        if (connection == null) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    private record RegisteredConnection(
        NotificationSseConnection connection,
        long registeredAtNanos,
        AtomicBoolean registrationObserved) {}
}
