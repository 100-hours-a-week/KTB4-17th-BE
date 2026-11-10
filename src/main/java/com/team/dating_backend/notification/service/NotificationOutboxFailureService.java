package com.team.dating_backend.notification.service;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.enums.NotificationFailureCode;
import com.team.dating_backend.notification.enums.NotificationOutboxStatus;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.outbox.NotificationOutboxRetryPolicy;
import com.team.dating_backend.notification.repository.NotificationRequestOutboxRepository;
import java.util.Optional;
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
    public Optional<FailureResult> recordFailure(Long id, String token, NotificationFailureCode failureType) {
        return record(id, token, failureType, true)
            .map(
                result -> new FailureResult(result.failureCount(), result.status() == NotificationOutboxStatus.FAILED));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<WorkerFailureResult> recordWorkerFailure(Long id, String token,
        NotificationFailureCode failureType, boolean retryable) {
        return record(id, token, failureType, retryable);
    }

    private Optional<WorkerFailureResult> record(Long id, String token,
        NotificationFailureCode failureType, boolean retryable) {
        if (id == null || id <= 0 || token == null || token.length() != 36 || token.isBlank()
            || failureType == null || !RECORDABLE_FAILURES.contains(failureType)) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
        var candidate = repository.findByIdForUpdate(id);
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        var outbox = candidate.get();
        if (outbox.getStatus() != NotificationOutboxStatus.PENDING || !token.equals(outbox.getProcessingToken())) {
            return Optional.empty();
        }
        int failures;
        long delayMicros;
        NotificationOutboxStatus status;
        try {
            failures = Math.addExact(outbox.getFailureCount(), 1);
            status = !retryable || retryPolicy.isExhausted(failures)
                ? NotificationOutboxStatus.FAILED
                : NotificationOutboxStatus.PENDING;
            delayMicros = status == NotificationOutboxStatus.FAILED
                ? 0L
                : Math.multiplyExact(retryPolicy.delayMillis(failures), 1000L);
        } catch (ArithmeticException failure) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR, failure);
        }
        int updated = repository.recordFailureByToken(id, token, outbox.getFailureCount(), failures,
            failureType.name(), delayMicros, status.name());
        return updated == 1 ? Optional.of(new WorkerFailureResult(failures, status)) : Optional.empty();
    }

    public record FailureResult(int failureCount, boolean exhausted) {}

    public record WorkerFailureResult(int failureCount, NotificationOutboxStatus status) {}
}
