package com.team.dating_backend.notification.alert;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.alert.NotificationFailureContext.KafkaFailureDetails;
import com.team.dating_backend.notification.alert.NotificationFailureContext.KafkaLocation;
import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.enums.NotificationFailureCode;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
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
        if (errorCode == null) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
        report(NotificationFailureContext.from(errorCode, request, exception, occurredAt(errorCode)));
    }

    public void report(NotificationFailureContext context) {
        if (context == null) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }

        safely("LOG", context.errorCode(), () -> logFailure(context));
        safely("METRIC", context.errorCode(), () -> meterRegistry
            .counter("notification.failures", "error_code", context.errorCode().name()).increment());

        if (shouldAlert(context.errorCode())) {
            safely("ALERT_DISPATCH", context.errorCode(), () -> alertDispatcher.dispatch(context));
        }
    }

    private void logFailure(NotificationFailureContext context) {
        KafkaFailureDetails details = context.kafkaDetails();
        log.error(
            "errorCode={}, failureStage={}, sourceEventId={}, recipientUserId={}, type={}, requestedAt={}, occurredAt={}, exceptionType={}, causeType={}, consumerGroup={}, originalLocation={}, inputLocation={}, destinationLocation={}, attempt={}, retryable={}, processingExceptionType={}, processingCauseType={}",
            context.errorCode(),
            context.failureStage(),
            valueForLog(context.sourceEventId(), 128),
            context.recipientUserId(),
            context.type(),
            context.requestedAt(),
            context.occurredAt(),
            valueForLog(context.exceptionType(), 128),
            valueForLog(context.causeType(), 128),
            details == null ? null : valueForLog(details.consumerGroup(), 255),
            details == null ? null : locationForLog(details.originalLocation()),
            details == null ? null : locationForLog(details.inputLocation()),
            details == null ? null : locationForLog(details.destinationLocation()),
            details == null ? null : details.attempt(),
            details == null ? null : details.retryable(),
            details == null ? null : valueForLog(details.processingExceptionType(), 128),
            details == null ? null : valueForLog(details.processingCauseType(), 128));
    }

    private boolean shouldAlert(NotificationFailureCode errorCode) {
        return switch (errorCode) {
            case RETRY_PUBLISH_FAILED, DLT_PUBLISH_FAILED, ALERT_SUBMISSION_REJECTED, ALERT_WEBHOOK_FAILED -> false;
            default -> true;
        };
    }

    private Instant occurredAt(NotificationFailureCode errorCode) {
        try {
            return Instant.ofEpochMilli(meterRegistry.config().clock().wallTime());
        } catch (RuntimeException exception) {
            recordReportingFailure("CONTEXT", errorCode, exception);
            return Instant.now();
        }
    }

    private void safely(String operation, NotificationFailureCode errorCode, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            recordReportingFailure(operation, errorCode, exception);
        }
    }

    private void recordReportingFailure(
        String operation,
        NotificationFailureCode errorCode,
        RuntimeException exception) {
        try {
            log.error("errorCode={}, originalErrorCode={}, reportOperation={}, exceptionType={}, causeType={}",
                CommonErrorCode.INTERNAL_SERVER_ERROR,
                errorCode,
                operation,
                valueForLog(exception.getClass().getSimpleName(), 128),
                valueForLog(exception.getCause() == null ? null : exception.getCause().getClass().getSimpleName(),
                    128));
        } catch (RuntimeException ignored) {}
        try {
            meterRegistry.counter("notification.reporting.failures", "operation", operation).increment();
        } catch (RuntimeException ignored) {}
    }

    private String locationForLog(KafkaLocation location) {
        return location == null
            ? null
            : valueForLog(location.topic(), 249) + ":" + location.partition() + ":" + location.offset();
    }

    private String valueForLog(String rawValue, int maxLength) {
        if (rawValue == null) {
            return null;
        }
        String value = rawValue.replaceAll("[\\p{Cntrl}\\p{Cf}\\p{Zl}\\p{Zp}]", " ");
        int length = Math.min(value.codePointCount(0, value.length()), maxLength);
        return value.substring(0, value.offsetByCodePoints(0, length));
    }
}
