package com.team.dating_backend.notification.outbox;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.alert.NotificationFailureContext;
import com.team.dating_backend.notification.alert.NotificationFailureReporter;
import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.entity.NotificationRequestOutbox;
import com.team.dating_backend.notification.enums.NotificationFailureCode;
import com.team.dating_backend.notification.enums.NotificationErrorCode;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.kafka.NotificationRequestProducer;
import com.team.dating_backend.notification.service.NotificationOutboxFailureService;
import com.team.dating_backend.notification.service.NotificationOutboxClaimService;
import com.team.dating_backend.notification.service.NotificationRequestOutboxService;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.common.errors.InterruptException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
public class NotificationOutboxRelay {

    private final NotificationRequestOutboxService outboxService;
    private final NotificationOutboxClaimService claimService;
    private final NotificationRequestProducer producer;
    private final NotificationOutboxFailureService failureService;
    private final NotificationOutboxFailureClassifier failureClassifier;
    private final NotificationFailureReporter failureReporter;

    @Transactional(propagation = Propagation.NEVER)
    public boolean publish(Long outboxId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new NotificationBusinessException(NotificationErrorCode.OUTBOX_PUBLISH_INTERRUPTED);
        }
        Optional<NotificationRequestOutbox> candidate;
        try {
            candidate = claimService.tryClaim(outboxId);
        } catch (NotificationBusinessException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new NotificationBusinessException(NotificationErrorCode.OUTBOX_CLAIM_FAILED, failure);
        }
        if (candidate.isEmpty()) {
            return false;
        }
        var outbox = candidate.get();
        NotificationCreateRequested request = null;
        boolean releaseRequired = true;
        Throwable originalFailure = null;
        try {
            if (Thread.currentThread().isInterrupted()) {
                throw interruptedFailure(outbox, null, new InterruptedException());
            }
            try {
                request = outboxService.restore(outbox);
            } catch (RuntimeException failure) {
                if (Thread.currentThread().isInterrupted()) {
                    throw interruptedFailure(outbox, null, failure);
                }
                releaseRequired = !recordAndReportFailure(outbox, null,
                    NotificationFailureCode.OUTBOX_RESTORE_FAILED, failure);
                throw new NotificationBusinessException(NotificationErrorCode.OUTBOX_RESTORE_FAILED, failure);
            }
            if (Thread.currentThread().isInterrupted()) {
                throw interruptedFailure(outbox, request, new InterruptedException());
            }
            try {
                if (producer.publishAndAwait(request) == null) {
                    throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
                }
            } catch (InterruptedException failure) {
                throw interruptedFailure(outbox, request, failure);
            } catch (ExecutionException | TimeoutException | RuntimeException failure) {
                if (Thread.currentThread().isInterrupted() || failure instanceof InterruptException) {
                    throw interruptedFailure(outbox, request, failure);
                }
                releaseRequired = !recordAndReportFailure(outbox, request, failureClassifier.classify(failure),
                    failure);
                throw new NotificationBusinessException(NotificationErrorCode.OUTBOX_PUBLISH_FAILED, failure);
            }
            if (Thread.currentThread().isInterrupted()) {
                throw interruptedFailure(outbox, request, new InterruptedException());
            }
            boolean published;
            try {
                published = claimService.markPublished(outboxId, outbox.getProcessingToken());
            } catch (RuntimeException failure) {
                if (Thread.currentThread().isInterrupted()) {
                    throw interruptedFailure(outbox, request, failure);
                }
                report(NotificationFailureCode.OUTBOX_STATE_UPDATE_FAILED, outbox, request, failure, null, null);
                throw new NotificationBusinessException(NotificationErrorCode.OUTBOX_STATE_UPDATE_FAILED, failure);
            }
            releaseRequired = false;
            if (!published) {
                throw claimLost(outbox, request, null);
            }
            return true;
        } catch (RuntimeException | Error failure) {
            originalFailure = failure;
            throw failure;
        } finally {
            if (releaseRequired) {
                release(outbox, request, originalFailure);
            }
        }
    }

    private NotificationBusinessException interruptedFailure(NotificationRequestOutbox outbox,
        NotificationCreateRequested request, Exception failure) {
        try {
            report(NotificationFailureCode.PUBLISH_INTERRUPTED, outbox, request, failure, null, true);
        } finally {
            Thread.currentThread().interrupt();
        }
        return new NotificationBusinessException(NotificationErrorCode.OUTBOX_PUBLISH_INTERRUPTED, failure);
    }

    private boolean recordAndReportFailure(NotificationRequestOutbox outbox,
        NotificationCreateRequested request, NotificationFailureCode errorCode, Exception failure) {
        Optional<NotificationOutboxFailureService.FailureResult> recorded;
        try {
            recorded = failureService.recordFailure(outbox.getId(), outbox.getProcessingToken(), errorCode);
        } catch (RuntimeException recordingFailure) {
            suppress(failure, recordingFailure);
            report(errorCode, outbox, request, failure, null, null);
            report(NotificationFailureCode.OUTBOX_STATE_UPDATE_FAILED, outbox, request, recordingFailure, null, null);
            return false;
        }
        if (recorded.isEmpty()) {
            throw claimLost(outbox, request, failure);
        }
        var result = recorded.get();
        report(errorCode, outbox, request, failure, result.failureCount(), !result.exhausted());
        if (result.exhausted()) {
            report(NotificationFailureCode.OUTBOX_RETRY_LIMIT_EXCEEDED, outbox, request, failure, result.failureCount(),
                false);
        }
        return true;
    }

    private NotificationBusinessException claimLost(NotificationRequestOutbox outbox,
        NotificationCreateRequested request, Exception cause) {
        var failure = new NotificationBusinessException(NotificationErrorCode.OUTBOX_CLAIM_LOST, cause);
        report(NotificationFailureCode.OUTBOX_CLAIM_LOST, outbox, request, failure, null, null);
        return failure;
    }

    private void release(NotificationRequestOutbox outbox, NotificationCreateRequested request, Throwable original) {
        boolean interrupted = Thread.interrupted();
        try {
            claimService.release(outbox.getId(), outbox.getProcessingToken());
        } catch (RuntimeException failure) {
            if (original != null) {
                suppress(original, failure);
            }
            report(NotificationFailureCode.OUTBOX_RELEASE_FAILED, outbox, request, failure, null, null);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void report(NotificationFailureCode errorCode, NotificationRequestOutbox outbox,
        NotificationCreateRequested request, Exception failure, Integer failureCount, Boolean retryable) {
        try {
            boolean alertRequested = errorCode == NotificationFailureCode.OUTBOX_RETRY_LIMIT_EXCEEDED;
            failureReporter.report(NotificationFailureContext.fromOutbox(errorCode, outbox, request, failure,
                Instant.now(), failureCount, retryable), alertRequested);
        } catch (RuntimeException reportingFailure) {
            suppress(failure, reportingFailure);
        }
    }

    private void suppress(Throwable original, RuntimeException additional) {
        if (original != additional) {
            original.addSuppressed(additional);
        }
    }
}
