package com.team.dating_backend.notification.outbox;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.alert.NotificationFailureContext;
import com.team.dating_backend.notification.alert.NotificationFailureReporter;
import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.entity.NotificationRequestOutbox;
import com.team.dating_backend.notification.enums.NotificationErrorCode;
import com.team.dating_backend.notification.enums.NotificationFailureCode;
import com.team.dating_backend.notification.enums.NotificationOutboxStatus;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.service.NotificationCreationProcessor;
import com.team.dating_backend.notification.service.NotificationOutboxClaimService;
import com.team.dating_backend.notification.service.NotificationOutboxFailureService;
import com.team.dating_backend.notification.service.NotificationRequestOutboxService;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationOutboxWorker {

    private final NotificationRequestOutboxService outboxService;
    private final NotificationCreationProcessor processor;
    private final NotificationOutboxClaimService claimService;
    private final NotificationOutboxFailureService failureService;
    private final NotificationOutboxWorkerFailureClassifier failureClassifier;
    private final NotificationFailureReporter failureReporter;

    @Transactional(propagation = Propagation.NEVER)
    public void process(NotificationRequestOutbox outbox)
        throws ExecutionException, InterruptedException, TimeoutException {
        if (outbox == null || outbox.getId() == null || outbox.getId() <= 0
            || outbox.getStatus() != NotificationOutboxStatus.PENDING
            || outbox.getProcessingToken() == null || outbox.getProcessingToken().length() != 36
            || outbox.getProcessingToken().isBlank() || outbox.getProcessingUntil() == null) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
        var failureType = new AtomicReference<>(NotificationFailureCode.OUTBOX_RESTORE_FAILED);
        NotificationCreateRequested request = null;
        try {
            requireNotInterrupted();
            request = outboxService.restore(outbox);
            requireNotInterrupted();
            failureType.set(NotificationFailureCode.OUTBOX_CREATION_FAILED);
            processor.process(request, stage -> failureType.set(stage == NotificationCreationProcessor.Stage.CREATION
                ? NotificationFailureCode.OUTBOX_CREATION_FAILED
                : NotificationFailureCode.OUTBOX_DELIVERY_FAILED));
            requireNotInterrupted();
            failureType.set(NotificationFailureCode.OUTBOX_STATE_UPDATE_FAILED);
            if (!claimService.markCompleted(outbox.getId(), outbox.getProcessingToken())) {
                throw new NotificationBusinessException(NotificationErrorCode.OUTBOX_CLAIM_LOST);
            }
        } catch (ExecutionException | InterruptedException | TimeoutException | RuntimeException failure) {
            handleFailure(outbox, request, failureType.get(), failure);
            throw failure;
        }
    }

    private void handleFailure(NotificationRequestOutbox outbox, NotificationCreateRequested request,
        NotificationFailureCode failureType, Exception failure) {
        var result = Thread.currentThread().isInterrupted()
            ? NotificationOutboxWorkerFailureClassifier.Result.INTERRUPTED
            : failureClassifier.classify(failure);
        if (result == NotificationOutboxWorkerFailureClassifier.Result.INTERRUPTED) {
            Thread.interrupted();
            try {
                claimService.release(outbox.getId(), outbox.getProcessingToken());
            } catch (RuntimeException releaseFailure) {
                suppress(failure, releaseFailure);
                report(NotificationFailureCode.OUTBOX_RELEASE_FAILED, outbox, request, releaseFailure,
                    outbox.getFailureCount(), NotificationOutboxStatus.PENDING, null);
            } finally {
                Thread.currentThread().interrupt();
            }
            report(NotificationFailureCode.PUBLISH_INTERRUPTED, outbox, request, failure,
                outbox.getFailureCount(), NotificationOutboxStatus.PENDING, null);
            return;
        }
        if (result == NotificationOutboxWorkerFailureClassifier.Result.CLAIM_LOST) {
            report(NotificationFailureCode.OUTBOX_CLAIM_LOST, outbox, request, failure, null, null, null);
            return;
        }
        boolean retryable = result == NotificationOutboxWorkerFailureClassifier.Result.RETRYABLE;
        Optional<NotificationOutboxFailureService.WorkerFailureResult> recorded;
        try {
            recorded = failureService.recordWorkerFailure(outbox.getId(), outbox.getProcessingToken(), failureType,
                retryable);
        } catch (RuntimeException recordingFailure) {
            suppress(failure, recordingFailure);
            report(failureType, outbox, request, failure, null, null, retryable);
            report(NotificationFailureCode.OUTBOX_STATE_UPDATE_FAILED, outbox, request, recordingFailure,
                null, null, null);
            return;
        }
        if (recorded.isEmpty()) {
            var lost = new NotificationBusinessException(NotificationErrorCode.OUTBOX_CLAIM_LOST, failure);
            report(NotificationFailureCode.OUTBOX_CLAIM_LOST, outbox, request, lost, null, null, null);
            throw lost;
        }
        var saved = recorded.get();
        report(failureType, outbox, request, failure, saved.failureCount(), saved.status(), retryable);
    }

    private void report(NotificationFailureCode errorCode, NotificationRequestOutbox outbox,
        NotificationCreateRequested request, Exception failure, Integer failureCount,
        NotificationOutboxStatus status, Boolean retryable) {
        try {
            failureReporter.report(NotificationFailureContext.fromWorker(errorCode, outbox, request, failure,
                Instant.now(), failureCount, status, retryable), status == NotificationOutboxStatus.FAILED);
        } catch (RuntimeException reportingFailure) {
            suppress(failure, reportingFailure);
        }
    }

    private void suppress(Exception original, RuntimeException additional) {
        if (original != additional) {
            original.addSuppressed(additional);
        }
    }

    private void requireNotInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }
    }
}
