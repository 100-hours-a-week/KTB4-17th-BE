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
import com.team.dating_backend.notification.service.NotificationRequestOutboxService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
public class NotificationOutboxRelay {

    private final NotificationRequestOutboxService outboxService;
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
            candidate = outboxService.findDueById(outboxId, LocalDateTime.now(ZoneOffset.UTC));
        } catch (NotificationBusinessException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new NotificationBusinessException(NotificationErrorCode.OUTBOX_LOOKUP_FAILED, failure);
        }
        if (candidate.isEmpty()) {
            return false;
        }
        var outbox = candidate.get();
        NotificationCreateRequested request;
        try {
            request = outboxService.restore(outbox);
        } catch (RuntimeException failure) {
            recordAndReportFailure(outboxId, outbox, null, NotificationFailureCode.OUTBOX_RESTORE_FAILED, failure);
            throw new NotificationBusinessException(NotificationErrorCode.OUTBOX_RESTORE_FAILED, failure);
        }
        try {
            if (producer.publishAndAwait(request) == null) {
                throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
            }
        } catch (InterruptedException failure) {
            try {
                report(NotificationFailureCode.PUBLISH_INTERRUPTED, outbox, request, failure, null, true);
            } finally {
                Thread.currentThread().interrupt();
            }
            throw new NotificationBusinessException(NotificationErrorCode.OUTBOX_PUBLISH_INTERRUPTED, failure);
        } catch (ExecutionException | TimeoutException | RuntimeException failure) {
            recordAndReportFailure(outboxId, outbox, request, failureClassifier.classify(failure), failure);
            throw new NotificationBusinessException(NotificationErrorCode.OUTBOX_PUBLISH_FAILED, failure);
        }
        try {
            outboxService.markPublished(outboxId, LocalDateTime.now(ZoneOffset.UTC));
        } catch (RuntimeException failure) {
            report(NotificationFailureCode.OUTBOX_STATE_UPDATE_FAILED, outbox, request, failure, null, null);
            throw new NotificationBusinessException(NotificationErrorCode.OUTBOX_STATE_UPDATE_FAILED, failure);
        }
        return true;
    }

    private void recordAndReportFailure(Long outboxId, NotificationRequestOutbox outbox,
        NotificationCreateRequested request, NotificationFailureCode errorCode, Exception failure) {
        NotificationOutboxFailureService.FailureResult result;
        try {
            result = failureService.recordFailure(outboxId, errorCode, LocalDateTime.now(ZoneOffset.UTC));
        } catch (RuntimeException recordingFailure) {
            suppress(failure, recordingFailure);
            report(errorCode, outbox, request, failure, null, null);
            report(NotificationFailureCode.OUTBOX_STATE_UPDATE_FAILED, outbox, request, recordingFailure, null, null);
            return;
        }
        report(errorCode, outbox, request, failure, result.failureCount(), !result.exhausted());
        if (result.exhausted()) {
            report(NotificationFailureCode.OUTBOX_RETRY_LIMIT_EXCEEDED, outbox, request, failure, result.failureCount(),
                false);
        }
    }

    private void report(NotificationFailureCode errorCode, NotificationRequestOutbox outbox,
        NotificationCreateRequested request, Exception failure, Integer failureCount, Boolean retryable) {
        try {
            failureReporter.report(NotificationFailureContext.fromOutbox(errorCode, outbox, request, failure,
                Instant.now(), failureCount, retryable));
        } catch (RuntimeException reportingFailure) {
            suppress(failure, reportingFailure);
        }
    }

    private void suppress(Exception original, RuntimeException additional) {
        if (original != additional) {
            original.addSuppressed(additional);
        }
    }
}
