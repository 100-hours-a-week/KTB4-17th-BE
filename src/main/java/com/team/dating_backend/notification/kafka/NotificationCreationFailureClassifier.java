package com.team.dating_backend.notification.kafka;

import com.team.dating_backend.notification.exception.NotificationInvalidCreateRequestException;
import java.sql.SQLException;
import java.sql.SQLNonTransientException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.errors.SerializationException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.stereotype.Component;

@Component
public class NotificationCreationFailureClassifier {

    private static final int MYSQL_LOCK_WAIT_TIMEOUT = 1205;
    private static final int MYSQL_DEADLOCK = 1213;
    private static final Set<String> NON_RETRYABLE_SQL_STATE_CLASSES = Set.of("0A", "22", "23", "28", "3D", "3F", "42");

    public boolean isRetryable(Throwable failure) {
        if (failure == null) {
            return false;
        }

        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        var pending = new ArrayDeque<Throwable>();
        pending.add(failure);
        boolean retryable = false;

        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            if (current instanceof NotificationInvalidCreateRequestException
                || current instanceof InterruptedException
                || current instanceof Error
                || current instanceof AuthenticationException
                || current instanceof AuthorizationException
                || current instanceof ConfigException
                || current instanceof SerializationException
                || current instanceof DeserializationException
                || current instanceof MessageConversionException
                || current instanceof InvalidDataAccessResourceUsageException) {
                return false;
            }

            if (current instanceof SQLException sqlException) {
                if (isNonRetryableSqlFailure(sqlException)) {
                    return false;
                }
                retryable |= isRetryableSqlFailure(sqlException);
                if (sqlException.getNextException() != null) {
                    pending.addLast(sqlException.getNextException());
                }
            }

            retryable |= current instanceof TransientDataAccessException
                || current instanceof RecoverableDataAccessException
                || current instanceof RetriableException
                || current instanceof TimeoutException;
            if (current.getCause() != null) {
                pending.addLast(current.getCause());
            }
        }

        return retryable;
    }

    private boolean isNonRetryableSqlFailure(SQLException exception) {
        String state = exception.getSQLState();
        if (state != null && state.length() >= 2
            && NON_RETRYABLE_SQL_STATE_CLASSES.contains(state.substring(0, 2))) {
            return true;
        }
        return exception instanceof SQLNonTransientException && !isRetryableSqlFailure(exception);
    }

    private boolean isRetryableSqlFailure(SQLException exception) {
        String state = exception.getSQLState();
        return exception instanceof SQLTransientException
            || exception instanceof SQLRecoverableException
            || exception.getErrorCode() == MYSQL_LOCK_WAIT_TIMEOUT
            || exception.getErrorCode() == MYSQL_DEADLOCK
            || (state != null && (state.startsWith("08") || state.startsWith("40")));
    }
}
