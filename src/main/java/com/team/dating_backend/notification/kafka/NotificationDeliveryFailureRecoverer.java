package com.team.dating_backend.notification.kafka;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.alert.NotificationFailureContext;
import com.team.dating_backend.notification.alert.NotificationFailureContext.KafkaFailureDetails;
import com.team.dating_backend.notification.alert.NotificationFailureContext.KafkaLocation;
import com.team.dating_backend.notification.alert.NotificationFailureReporter;
import com.team.dating_backend.notification.config.NotificationKafkaProperties;
import com.team.dating_backend.notification.dto.event.NotificationDeliveryEvent;
import com.team.dating_backend.notification.enums.NotificationFailureCode;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.exception.NotificationInvalidDeliveryEventException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.TimestampedException;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationDeliveryFailureRecoverer implements ConsumerRecordRecoverer {

    private final NotificationFailureReporter failureReporter;
    private final NotificationKafkaProperties kafkaProperties;
    private final MeterRegistry meterRegistry;

    @Override
    public void accept(ConsumerRecord<?, ?> record, Exception exception) {
        if (record == null || exception == null || !kafkaProperties.getDeliveryTopic().equals(record.topic())) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR, exception);
        }
        if (Thread.currentThread().isInterrupted()) {
            throw unhandled(exception);
        }

        NotificationFailureCode errorCode = failureCode(exception);
        reportSafely(record, exception, errorCode);
        if (Thread.currentThread().isInterrupted() || errorCode == NotificationFailureCode.DELIVERY_PROCESSING_FAILED) {
            throw unhandled(exception);
        }
    }

    private NotificationFailureCode failureCode(Exception exception) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = exception;
        boolean invalidEvent = false;
        boolean deserializationFailure = false;
        while (current != null && visited.add(current)) {
            if (current instanceof Error error) {
                throw error;
            }
            if (current instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                throw unhandled(exception);
            }
            invalidEvent |= current instanceof NotificationInvalidDeliveryEventException;
            deserializationFailure |= current instanceof DeserializationException;
            current = current.getCause();
        }
        if (deserializationFailure) {
            return NotificationFailureCode.DELIVERY_DESERIALIZATION_FAILED;
        }
        return invalidEvent
            ? NotificationFailureCode.DELIVERY_INVALID_EVENT
            : NotificationFailureCode.DELIVERY_PROCESSING_FAILED;
    }

    private void reportSafely(
        ConsumerRecord<?, ?> record, Exception exception, NotificationFailureCode errorCode) {
        try {
            Integer partition = record.partition() < 0 ? null : record.partition();
            Long offset = partition == null || record.offset() < 0 ? null : Long.valueOf(record.offset());
            Throwable processingFailure = processingFailure(exception);
            var details = KafkaFailureDetails.from(
                consumerGroup(exception),
                null,
                new KafkaLocation(record.topic(), partition, offset),
                null,
                null,
                errorCode == NotificationFailureCode.DELIVERY_PROCESSING_FAILED ? null : Boolean.FALSE,
                processingFailure);
            NotificationDeliveryEvent event = record.value() instanceof NotificationDeliveryEvent value
                ? value
                : null;
            failureReporter.report(NotificationFailureContext.fromDelivery(
                errorCode, event, processingFailure, Instant.now(), details));
        } catch (RuntimeException failure) {
            recordReportingFailure(record, failure);
        }
    }

    private String consumerGroup(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        while (current != null && visited.add(current)) {
            if (current instanceof ListenerExecutionFailedException listenerFailure
                && listenerFailure.getGroupId() != null && !listenerFailure.getGroupId().isBlank()) {
                return listenerFailure.getGroupId();
            }
            current = current.getCause();
        }
        return null;
    }

    private Throwable processingFailure(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        while (current != null && visited.add(current)
            && (current instanceof ListenerExecutionFailedException || current instanceof TimestampedException)
            && current.getCause() != null && !visited.contains(current.getCause())) {
            current = current.getCause();
        }
        return current;
    }

    private RuntimeException unhandled(Exception exception) {
        return exception instanceof RuntimeException runtimeException
            ? runtimeException
            : new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR, exception);
    }

    private void recordReportingFailure(ConsumerRecord<?, ?> record, RuntimeException failure) {
        try {
            log.error(
                "errorCode={}, reportOperation=DELIVERY_CONTEXT, inputTopic={}, inputPartition={}, inputOffset={}, exceptionType={}, causeType={}",
                CommonErrorCode.INTERNAL_SERVER_ERROR,
                record.topic(),
                record.partition(),
                record.offset(),
                failure.getClass().getSimpleName(),
                failure.getCause() == null ? null : failure.getCause().getClass().getSimpleName());
        } catch (RuntimeException ignored) {}
        try {
            meterRegistry.counter("notification.reporting.failures", "operation", "DELIVERY_CONTEXT").increment();
        } catch (RuntimeException ignored) {}
    }
}
