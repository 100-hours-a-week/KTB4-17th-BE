package com.team.dating_backend.notification.alert;

import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.enums.NotificationFailureCode;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationFailureReporter {

    private final NotificationAlertDispatcher alertDispatcher;
    private final MeterRegistry meterRegistry;

    public void report(
        NotificationFailureCode errorCode,
        NotificationCreateRequested request,
        Throwable exception) {
        NotificationFailureContext context = NotificationFailureContext.from(
            errorCode,
            request,
            exception,
            Instant.ofEpochMilli(meterRegistry.config().clock().wallTime()));

        log.error(
            "Notification processing failed. errorCode={}, sourceEventId={}, recipientUserId={}, type={}, requestedAt={}, occurredAt={}, exceptionType={}, causeType={}",
            context.errorCode(),
            sourceEventIdForLog(context.sourceEventId()),
            context.recipientUserId(),
            context.type(),
            context.requestedAt(),
            context.occurredAt(),
            context.exceptionType(),
            context.causeType());
        meterRegistry.counter("notification.failures", "error_code", context.errorCode().name()).increment();

        if (errorCode != NotificationFailureCode.ALERT_SUBMISSION_REJECTED
            && errorCode != NotificationFailureCode.ALERT_WEBHOOK_FAILED) {
            alertDispatcher.dispatch(context);
        }
    }

    private String sourceEventIdForLog(String sourceEventId) {
        if (sourceEventId == null) {
            return null;
        }
        String value = sourceEventId.replaceAll("\\p{Cntrl}", " ");
        int length = Math.min(value.codePointCount(0, value.length()), 128);
        return value.substring(0, value.offsetByCodePoints(0, length));
    }
}
