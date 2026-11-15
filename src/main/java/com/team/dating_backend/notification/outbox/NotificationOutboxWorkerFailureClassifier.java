package com.team.dating_backend.notification.outbox;

import com.team.dating_backend.notification.enums.NotificationErrorCode;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
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
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.errors.SerializationException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.stereotype.Component;

@Component
public class NotificationOutboxWorkerFailureClassifier {

    private static final int MYSQL_LOCK_WAIT_TIMEOUT = 1205;
    private static final int MYSQL_DEADLOCK = 1213;
    private static final Set<String> NON_RETRYABLE_SQL_STATE_CLASSES = Set.of("0A", "22", "23", "28", "3D", "3F", "42");

    public Result classify(Throwable failure) {
        if (failure == null) {
            return Result.NON_RETRYABLE;
        }
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        var pending = new ArrayDeque<Throwable>();
        pending.add(failure);
        boolean claimLost = false;
        boolean nonRetryable = false;
        boolean retryable = false;
        while (!pending.isEmpty()) {
            var current = pending.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            if (current instanceof InterruptedException || current instanceof InterruptException) {
                return Result.INTERRUPTED;
            }
            if (current instanceof NotificationBusinessException business) {
                claimLost |= business.getErrorCode() == NotificationErrorCode.OUTBOX_CLAIM_LOST;
            }
            nonRetryable |= current instanceof IllegalArgumentException
                || current instanceof UnsupportedOperationException
                || current instanceof NotificationInvalidCreateRequestException
                || current instanceof Error
                || current instanceof AuthenticationException
                || current instanceof AuthorizationException
                || current instanceof ConfigException
                || current instanceof SerializationException
                || current instanceof DeserializationException
                || current instanceof MessageConversionException
                || current instanceof InvalidDataAccessResourceUsageException;
            if (current instanceof SQLException sql) {
                String state = sql.getSQLState();
                boolean transientSql = sql instanceof SQLTransientException
                    || sql instanceof SQLRecoverableException
                    || sql.getErrorCode() == MYSQL_LOCK_WAIT_TIMEOUT
                    || sql.getErrorCode() == MYSQL_DEADLOCK
                    || (state != null && (state.startsWith("08") || state.startsWith("40")));
                nonRetryable |= (state != null && state.length() >= 2
                    && NON_RETRYABLE_SQL_STATE_CLASSES.contains(state.substring(0, 2)))
                    || (sql instanceof SQLNonTransientException && !transientSql);
                retryable |= transientSql;
                if (sql.getNextException() != null) {
                    pending.addLast(sql.getNextException());
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
        if (claimLost) {
            return Result.CLAIM_LOST;
        }
        return !nonRetryable && retryable ? Result.RETRYABLE : Result.NON_RETRYABLE;
    }

    public enum Result {
        RETRYABLE,
        NON_RETRYABLE,
        INTERRUPTED,
        CLAIM_LOST
    }
}
