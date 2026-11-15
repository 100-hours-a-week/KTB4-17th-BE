package com.team.dating_backend.notification.outbox;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.alert.NotificationFailureContext;
import com.team.dating_backend.notification.alert.NotificationFailureReporter;
import com.team.dating_backend.notification.config.NotificationOutboxWorkerProperties;
import com.team.dating_backend.notification.entity.NotificationRequestOutbox;
import com.team.dating_backend.notification.enums.NotificationFailureCode;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.service.NotificationOutboxClaimService;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.notification.outbox.worker", name = "enabled", havingValue = "true")
public class NotificationOutboxWorkerJob {

    private final NotificationOutboxClaimService claimService;
    private final NotificationOutboxWorker worker;
    private final NotificationOutboxWorkerProperties properties;
    private final NotificationFailureReporter failureReporter;
    private final AtomicBoolean running = new AtomicBoolean();

    @Transactional(propagation = Propagation.NEVER)
    @Scheduled(
        scheduler = "notificationOutboxWorkerScheduler",
        fixedDelayString = "${app.notification.outbox.worker.fixed-delay-ms:1000}",
        initialDelayString = "${app.notification.outbox.worker.fixed-delay-ms:1000}"
    )
    public void processDueRequests() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
        if (!properties.isEnabled() || Thread.currentThread().isInterrupted() || !running.compareAndSet(false, true)) {
            return;
        }
        try {
            processBatch();
        } finally {
            running.set(false);
        }
    }

    private void processBatch() {
        for (int processed = 0; processed < properties.getMaxRequestsPerRun(); processed++) {
            if (!properties.isEnabled() || Thread.currentThread().isInterrupted()) {
                return;
            }
            Optional<NotificationRequestOutbox> candidate;
            try {
                candidate = claimService.tryClaimNext();
            } catch (RuntimeException failure) {
                reportClaimFailure(failure);
                return;
            }
            if (candidate.isEmpty()) {
                return;
            }
            try {
                worker.process(candidate.get());
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException | TimeoutException | RuntimeException failure) {
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
            }
        }
    }

    private void reportClaimFailure(RuntimeException failure) {
        if (Thread.currentThread().isInterrupted()) {
            return;
        }
        try {
            failureReporter.report(NotificationFailureContext.from(NotificationFailureCode.OUTBOX_CLAIM_FAILED,
                null, failure, Instant.now()), false);
        } catch (RuntimeException reportingFailure) {
            if (failure != reportingFailure) {
                failure.addSuppressed(reportingFailure);
            }
        }
    }
}
