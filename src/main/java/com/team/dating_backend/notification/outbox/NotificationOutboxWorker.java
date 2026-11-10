package com.team.dating_backend.notification.outbox;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.entity.NotificationRequestOutbox;
import com.team.dating_backend.notification.enums.NotificationErrorCode;
import com.team.dating_backend.notification.enums.NotificationFailureCode;
import com.team.dating_backend.notification.enums.NotificationOutboxStatus;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.service.NotificationCreationProcessor;
import com.team.dating_backend.notification.service.NotificationOutboxClaimService;
import com.team.dating_backend.notification.service.NotificationOutboxFailureService;
import com.team.dating_backend.notification.service.NotificationRequestOutboxService;
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
        try {
            requireNotInterrupted();
            var request = outboxService.restore(outbox);
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
            handleFailure(outbox, failureType.get(), failure);
            throw failure;
        }
    }

    private void handleFailure(NotificationRequestOutbox outbox, NotificationFailureCode failureType,
        Exception failure) {
        var result = Thread.currentThread().isInterrupted()
            ? NotificationOutboxWorkerFailureClassifier.Result.INTERRUPTED
            : failureClassifier.classify(failure);
        if (result == NotificationOutboxWorkerFailureClassifier.Result.INTERRUPTED) {
            Thread.interrupted();
            try {
                claimService.release(outbox.getId(), outbox.getProcessingToken());
            } catch (RuntimeException releaseFailure) {
                suppress(failure, releaseFailure);
            } finally {
                Thread.currentThread().interrupt();
            }
            return;
        }
        if (result == NotificationOutboxWorkerFailureClassifier.Result.CLAIM_LOST) {
            return;
        }
        boolean recorded;
        try {
            recorded = failureService.recordWorkerFailure(outbox.getId(), outbox.getProcessingToken(), failureType,
                result == NotificationOutboxWorkerFailureClassifier.Result.RETRYABLE).isPresent();
        } catch (RuntimeException recordingFailure) {
            suppress(failure, recordingFailure);
            return;
        }
        if (!recorded) {
            throw new NotificationBusinessException(NotificationErrorCode.OUTBOX_CLAIM_LOST, failure);
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
