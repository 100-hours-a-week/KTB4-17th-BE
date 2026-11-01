package com.team.dating_backend.notification.outbox;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.alert.NotificationFailureContext;
import com.team.dating_backend.notification.alert.NotificationFailureReporter;
import com.team.dating_backend.notification.config.NotificationOutboxRelayProperties;
import com.team.dating_backend.notification.entity.NotificationRequestOutbox;
import com.team.dating_backend.notification.enums.NotificationErrorCode;
import com.team.dating_backend.notification.enums.NotificationFailureCode;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.service.NotificationRequestOutboxService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.notification.outbox.relay", name = "enabled", havingValue = "true")
public class NotificationOutboxPublishJob {

    private final NotificationRequestOutboxService outboxService;
    private final NotificationOutboxRelay relay;
    private final NotificationOutboxRelayProperties properties;
    private final NotificationFailureReporter failureReporter;

    @Transactional(propagation = Propagation.NEVER)
    @Scheduled(
        scheduler = "notificationOutboxScheduler",
        fixedDelayString = "${app.notification.outbox.relay.fixed-delay-ms:1000}",
        initialDelayString = "${app.notification.outbox.relay.fixed-delay-ms:1000}"
    )
    public void publishDueRequests() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
        if (!properties.isEnabled() || Thread.currentThread().isInterrupted()) {
            return;
        }
        List<NotificationRequestOutbox> candidates;
        try {
            candidates = outboxService.findDue(LocalDateTime.now(ZoneOffset.UTC), properties.getBatchSize());
        } catch (RuntimeException failure) {
            if (!Thread.currentThread().isInterrupted()) {
                reportFailure(NotificationFailureCode.OUTBOX_LOOKUP_FAILED, null, failure);
            }
            return;
        }
        for (var outbox : candidates) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            try {
                relay.publish(outbox.getId());
            } catch (RuntimeException failure) {
                if (failure instanceof NotificationBusinessException businessFailure
                    && businessFailure.getErrorCode() == NotificationErrorCode.OUTBOX_PUBLISH_INTERRUPTED) {
                    Thread.currentThread().interrupt();
                }
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                reportUnreportedFailure(outbox, failure);
            }
        }
    }

    private void reportUnreportedFailure(NotificationRequestOutbox outbox, RuntimeException failure) {
        if (failure instanceof NotificationBusinessException businessFailure) {
            var code = businessFailure.getErrorCode();
            if (code == NotificationErrorCode.OUTBOX_RESTORE_FAILED
                || code == NotificationErrorCode.OUTBOX_PUBLISH_FAILED
                || code == NotificationErrorCode.OUTBOX_STATE_UPDATE_FAILED) {
                return;
            }
            if (code == NotificationErrorCode.OUTBOX_LOOKUP_FAILED) {
                reportFailure(NotificationFailureCode.OUTBOX_LOOKUP_FAILED, outbox, failure);
                return;
            }
        }
        reportFailure(NotificationFailureCode.OUTBOX_RELAY_FAILED, outbox, failure);
    }

    private void reportFailure(NotificationFailureCode code, NotificationRequestOutbox outbox,
        RuntimeException failure) {
        try {
            var context = outbox == null
                ? NotificationFailureContext.from(code, null, failure, Instant.now())
                : NotificationFailureContext.fromOutbox(code, outbox, null, failure, Instant.now(), null, null);
            failureReporter.report(context);
        } catch (RuntimeException reportingFailure) {
            if (failure != reportingFailure) {
                failure.addSuppressed(reportingFailure);
            }
        }
    }
}
