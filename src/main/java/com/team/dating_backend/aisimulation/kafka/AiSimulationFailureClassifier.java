package com.team.dating_backend.aisimulation.kafka;

import com.team.dating_backend.aisimulation.enums.AiSimulationErrorCode;
import com.team.dating_backend.aisimulation.exception.AiSimulationBusinessException;
import java.sql.SQLException;
import java.sql.SQLNonTransientException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLHandshakeException;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.errors.SerializationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class AiSimulationFailureClassifier {

    private static final int MYSQL_LOCK_WAIT_TIMEOUT = 1205;
    private static final int MYSQL_DEADLOCK = 1213;
    private static final Set<AiSimulationErrorCode> RETRYABLE_AI_ERRORS = Set.of(
        AiSimulationErrorCode.AI_SERVER_UNAVAILABLE, AiSimulationErrorCode.SIMULATION_GENERATION_FAILED,
        AiSimulationErrorCode.SIMULATION_ALREADY_RUNNING);
    private static final Set<Integer> RETRYABLE_HTTP_STATUSES = Set.of(408, 409, 429, 500, 502, 503, 504);
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
            if (isTerminal(current)) {
                return false;
            }
            if (current instanceof AiSimulationBusinessException business) {
                if (isTerminalBusinessFailure(business)) {
                    return false;
                }
                retryable |= RETRYABLE_AI_ERRORS.contains(business.getErrorCode());
            }
            if (current instanceof RestClientResponseException response) {
                if (!RETRYABLE_HTTP_STATUSES.contains(response.getStatusCode().value())) {
                    return false;
                }
                retryable = true;
            }
            if (current instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && state.length() >= 2
                    && NON_RETRYABLE_SQL_STATE_CLASSES.contains(state.substring(0, 2))) {
                    return false;
                }
                boolean transientSql = sql instanceof SQLTransientException
                    || sql instanceof SQLRecoverableException
                    || sql.getErrorCode() == MYSQL_LOCK_WAIT_TIMEOUT || sql.getErrorCode() == MYSQL_DEADLOCK
                    || state != null && (state.startsWith("08") || state.startsWith("40"));
                if (sql instanceof SQLNonTransientException && !transientSql) {
                    return false;
                }
                retryable |= transientSql;
                if (sql.getNextException() != null) {
                    pending.addLast(sql.getNextException());
                }
            }
            retryable |= current instanceof TransientDataAccessException
                || current instanceof RecoverableDataAccessException
                || current instanceof RetriableException
                || current instanceof TimeoutException
                || current instanceof ResourceAccessException;
            if (current.getCause() != null) {
                pending.addLast(current.getCause());
            }
        }
        return retryable;
    }

    private boolean isTerminal(Throwable failure) {
        return failure instanceof InterruptedException
            || failure instanceof Error
            || failure instanceof IllegalArgumentException
            || failure instanceof AuthenticationException
            || failure instanceof AuthorizationException
            || failure instanceof ConfigException
            || failure instanceof SerializationException
            || failure instanceof DeserializationException
            || failure instanceof MessageConversionException
            || failure instanceof DataIntegrityViolationException
            || failure instanceof InvalidDataAccessResourceUsageException
            || failure instanceof SSLHandshakeException;
    }

    private boolean isTerminalBusinessFailure(AiSimulationBusinessException failure) {
        if (failure.getErrorCode() == null) {
            return true;
        }
        if (failure.getErrorCode() == AiSimulationErrorCode.AI_SERVER_RESPONSE_INVALID) {
            return !(failure.getCause() instanceof RestClientResponseException response
                && RETRYABLE_HTTP_STATUSES.contains(response.getStatusCode().value()));
        }
        return !RETRYABLE_AI_ERRORS.contains(failure.getErrorCode())
            && failure.getErrorCode() != AiSimulationErrorCode.SIMULATION_EVENT_PUBLISH_FAILED
            && failure.getErrorCode() != AiSimulationErrorCode.SIMULATION_PROCESSING_FAILED;
    }
}
