package com.team.dating_backend.notification.sse;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.dto.event.NotificationDeliveryEvent;
import com.team.dating_backend.notification.enums.NotificationSseCloseReason;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.record.TimestampType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationSseObservation {

    private final ObjectProvider<MeterRegistry> meterRegistryProvider;
    private final NotificationSseProcessIdentity processIdentity;

    public void connectionRegistered(NotificationSseConnection connection) {
        if (!require(connection != null, "CONNECTION_REGISTERED")) {
            return;
        }
        observe("CONNECTION_REGISTERED_METRIC", () -> meterRegistryProvider.getObject()
            .counter("notification.sse.connections.registered").increment());
        observe("CONNECTION_REGISTERED_LOG", () -> log.info(
            "SSE connection registered. processId={}, userId={}, connectionId={}",
            processIdentity.getProcessId(), connection.getUserId(), connection.getConnectionId()));
    }

    public void connectionClosed(
        NotificationSseConnection connection,
        NotificationSseCloseReason reason,
        long lifetimeNanos) {
        if (!require(connection != null && reason != null && lifetimeNanos >= 0, "CONNECTION_CLOSED")) {
            return;
        }
        observe("CONNECTION_CLOSED_METRIC", () -> meterRegistryProvider.getObject()
            .counter("notification.sse.connections.closed", "reason", reason.name()).increment());
        observe("CONNECTION_DURATION_METRIC", () -> meterRegistryProvider.getObject()
            .timer("notification.sse.connection.duration", "reason", reason.name())
            .record(lifetimeNanos, TimeUnit.NANOSECONDS));
        observe("CONNECTION_CLOSED_LOG", () -> log.info(
            "SSE connection closed. processId={}, userId={}, connectionId={}, reason={}, lifetimeNanos={}",
            processIdentity.getProcessId(), connection.getUserId(), connection.getConnectionId(), reason,
            lifetimeNanos));
    }

    public void initializationFailed(NotificationSseConnection connection, Throwable exception) {
        if (!require(connection != null && exception != null, "INITIALIZATION_FAILED")) {
            return;
        }
        observe("INITIALIZATION_FAILED_METRIC", () -> meterRegistryProvider.getObject()
            .counter("notification.sse.initialization.failures").increment());
        observe("INITIALIZATION_FAILED_LOG", () -> log.error(
            "errorCode={}, processId={}, userId={}, connectionId={}, reason={}, exceptionType={}, causeType={}",
            CommonErrorCode.INTERNAL_SERVER_ERROR, processIdentity.getProcessId(), connection.getUserId(),
            connection.getConnectionId(), NotificationSseCloseReason.INITIALIZATION_FAILURE,
            exception.getClass().getSimpleName(),
            exception.getCause() == null ? null : exception.getCause().getClass().getSimpleName()));
    }

    public void sendFailed(
        NotificationSseConnection connection,
        EventKind eventKind,
        NotificationSseCloseReason reason,
        Throwable exception) {
        if (!require(connection != null && eventKind != null && exception != null
            && (reason == NotificationSseCloseReason.TRANSPORT_ERROR
                || reason == NotificationSseCloseReason.SERVER_ERROR),
            "SEND_FAILED")) {
            return;
        }
        observe("SEND_FAILED_METRIC", () -> meterRegistryProvider.getObject()
            .counter("notification.sse.send.failures", "event", eventKind.name(), "reason", reason.name())
            .increment());
        observe("SEND_FAILED_LOG", () -> {
            if (reason == NotificationSseCloseReason.TRANSPORT_ERROR) {
                log.debug(
                    "SSE transport write failed. processId={}, userId={}, connectionId={}, event={}, reason={}, exceptionType={}",
                    processIdentity.getProcessId(), connection.getUserId(), connection.getConnectionId(), eventKind,
                    reason, exception.getClass().getSimpleName());
            } else {
                log.error(
                    "errorCode={}, processId={}, userId={}, connectionId={}, event={}, reason={}, exceptionType={}, causeType={}",
                    CommonErrorCode.INTERNAL_SERVER_ERROR, processIdentity.getProcessId(), connection.getUserId(),
                    connection.getConnectionId(), eventKind, reason, exception.getClass().getSimpleName(),
                    exception.getCause() == null ? null : exception.getCause().getClass().getSimpleName());
            }
        });
    }

    public void deliveryReceived(ConsumerRecord<String, NotificationDeliveryEvent> record) {
        if (!require(record != null, "DELIVERY_RECEIVED")) {
            return;
        }
        observe("DELIVERY_RECORD_AGE_METRIC", () -> {
            MeterRegistry registry = meterRegistryProvider.getObject();
            if (record.timestampType() == null || record.timestampType() == TimestampType.NO_TIMESTAMP_TYPE
                || record.timestamp() < 0) {
                registry.counter("notification.delivery.record.age.unavailable", "reason", "MISSING").increment();
                return;
            }
            long now = registry.config().clock().wallTime();
            if (record.timestamp() > now) {
                registry.counter("notification.delivery.record.age.unavailable", "reason", "FUTURE").increment();
                return;
            }
            registry.timer("notification.delivery.record.age", "timestamp_type", record.timestampType().name())
                .record(now - record.timestamp(), TimeUnit.MILLISECONDS);
        });
    }

    public void deliveryProcessed(
        ConsumerRecord<String, NotificationDeliveryEvent> record,
        Integer localConnectionCount,
        long processingNanos,
        Throwable exception) {
        if (!require(record != null && processingNanos >= 0
            && (localConnectionCount == null || localConnectionCount >= 0), "DELIVERY_PROCESSED")) {
            return;
        }
        String result = exception == null ? "PROCESSED" : "FAILED";
        observe("DELIVERY_PROCESSING_METRIC", () -> meterRegistryProvider.getObject()
            .timer("notification.delivery.processing", "result", result)
            .record(processingNanos, TimeUnit.NANOSECONDS));
        observe("DELIVERY_PROCESSED_LOG", () -> {
            NotificationDeliveryEvent event = record.value();
            log.debug(
                "Delivery processing finished. processId={}, consumerGroup={}, topic={}, partition={}, offset={}, recipientUserId={}, notificationId={}, localConnectionCount={}, result={}, processingNanos={}, exceptionType={}, causeType={}",
                processIdentity.getProcessId(), processIdentity.getGroupId(), record.topic(), record.partition(),
                record.offset(), event == null ? null : event.recipientUserId(),
                event == null || event.notification() == null ? null : event.notification().notificationId(),
                localConnectionCount, result, processingNanos,
                exception == null ? null : exception.getClass().getSimpleName(),
                exception == null || exception.getCause() == null
                    ? null
                    : exception.getCause().getClass().getSimpleName());
        });
    }

    private boolean require(boolean valid, String operation) {
        if (!valid) {
            recordObservationFailure(operation,
                new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR));
        }
        return valid;
    }

    private void observe(String operation, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            recordObservationFailure(operation, exception);
        }
    }

    private void recordObservationFailure(String operation, RuntimeException exception) {
        try {
            log.error("errorCode={}, observationOperation={}, exceptionType={}, causeType={}",
                CommonErrorCode.INTERNAL_SERVER_ERROR, operation, exception.getClass().getSimpleName(),
                exception.getCause() == null ? null : exception.getCause().getClass().getSimpleName());
        } catch (RuntimeException ignored) {}
        try {
            meterRegistryProvider.getObject()
                .counter("notification.sse.observation.failures", "operation", operation).increment();
        } catch (RuntimeException ignored) {}
    }

    public enum EventKind {
        CONNECTED,
        NOTIFICATION,
        HEARTBEAT;
    }
}
