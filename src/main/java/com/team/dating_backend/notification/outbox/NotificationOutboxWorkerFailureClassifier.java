package com.team.dating_backend.notification.outbox;

import com.team.dating_backend.notification.enums.NotificationErrorCode;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.kafka.NotificationCreationFailureClassifier;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.common.errors.InterruptException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NotificationOutboxWorkerFailureClassifier {

    private final NotificationCreationFailureClassifier creationFailureClassifier;

    public Result classify(Throwable failure) {
        if (failure == null) {
            return Result.NON_RETRYABLE;
        }
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        var pending = new ArrayDeque<Throwable>();
        pending.add(failure);
        boolean claimLost = false;
        boolean nonRetryable = false;
        while (!pending.isEmpty()) {
            var current = pending.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            if (current instanceof InterruptedException || current instanceof InterruptException) {
                return Result.INTERRUPTED;
            }
            if (current instanceof NotificationBusinessException business) {
                if (business.getErrorCode() == NotificationErrorCode.OUTBOX_PUBLISH_INTERRUPTED) {
                    return Result.INTERRUPTED;
                }
                claimLost |= business.getErrorCode() == NotificationErrorCode.OUTBOX_CLAIM_LOST;
            }
            nonRetryable |= current instanceof IllegalArgumentException
                || current instanceof UnsupportedOperationException;
            if (current instanceof SQLException sql && sql.getNextException() != null) {
                pending.addLast(sql.getNextException());
            }
            if (current.getCause() != null) {
                pending.addLast(current.getCause());
            }
        }
        if (claimLost) {
            return Result.CLAIM_LOST;
        }
        return !nonRetryable && creationFailureClassifier.isRetryable(failure)
            ? Result.RETRYABLE
            : Result.NON_RETRYABLE;
    }

    public enum Result {
        RETRYABLE,
        NON_RETRYABLE,
        INTERRUPTED,
        CLAIM_LOST
    }
}
