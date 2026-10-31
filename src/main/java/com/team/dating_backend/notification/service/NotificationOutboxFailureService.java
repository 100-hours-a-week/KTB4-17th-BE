package com.team.dating_backend.notification.service;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.enums.NotificationFailureCode;
import com.team.dating_backend.notification.enums.NotificationOutboxStatus;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.outbox.NotificationOutboxRetryPolicy;
import com.team.dating_backend.notification.repository.NotificationRequestOutboxRepository;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationOutboxFailureService {

    private static final Set<NotificationFailureCode> RECORDABLE_FAILURES = Set.of(
        NotificationFailureCode.OUTBOX_RESTORE_FAILED,
        NotificationFailureCode.OUTBOX_PUBLISH_REQUIRES_ATTENTION,
        NotificationFailureCode.PUBLISH_CALL_FAILED,
        NotificationFailureCode.PUBLISH_RESULT_FAILED,
        NotificationFailureCode.PUBLISH_RESULT_TIMEOUT);

    private final NotificationRequestOutboxRepository repository;
    private final NotificationOutboxRetryPolicy retryPolicy;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FailureResult recordFailure(Long id, NotificationFailureCode failureType, LocalDateTime failedAt) {
        if (id == null || id <= 0 || failedAt == null || failureType == null
            || !RECORDABLE_FAILURES.contains(failureType)) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
        var outbox = repository.findById(id)
            .orElseThrow(() -> new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR));
        if (outbox.getStatus() != NotificationOutboxStatus.PENDING) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
        int failures = Math.addExact(outbox.getFailureCount(), 1);
        boolean exhausted = retryPolicy.isExhausted(failures);
        LocalDateTime nextAttemptAt = (exhausted
            ? failedAt
            : failedAt.plus(retryPolicy.delayMillis(failures), ChronoUnit.MILLIS))
            .truncatedTo(ChronoUnit.MICROS);
        int updated = repository.recordFailure(id, outbox.getFailureCount(), failures, failureType.name(),
            nextAttemptAt,
            NotificationOutboxStatus.PENDING,
            exhausted ? NotificationOutboxStatus.FAILED : NotificationOutboxStatus.PENDING);
        if (updated != 1) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
        return new FailureResult(failures, exhausted);
    }

    public record FailureResult(int failureCount, boolean exhausted) {}
}
