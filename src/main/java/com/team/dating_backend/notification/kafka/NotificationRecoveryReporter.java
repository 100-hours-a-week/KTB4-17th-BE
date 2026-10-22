package com.team.dating_backend.notification.kafka;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.alert.NotificationFailureContext;
import com.team.dating_backend.notification.alert.NotificationFailureContext.KafkaFailureDetails;
import com.team.dating_backend.notification.alert.NotificationFailureContext.KafkaLocation;
import com.team.dating_backend.notification.alert.NotificationFailureReporter;
import com.team.dating_backend.notification.config.NotificationKafkaProperties;
import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.enums.NotificationFailureCode;
import com.team.dating_backend.notification.exception.NotificationRetryableCreationException;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.TimestampedException;
import org.springframework.kafka.retrytopic.RetryTopicHeaders;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationRecoveryReporter {

    private final NotificationFailureReporter failureReporter;
    private final NotificationCreationFailureClassifier failureClassifier;
    private final NotificationKafkaProperties kafkaProperties;
    private final MeterRegistry meterRegistry;

    public DeadLetterPublishingRecoverer createRecoverer(
        Function<ProducerRecord<?, ?>, ? extends KafkaOperations<?, ?>> templateResolver,
        BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> destinationResolver) {
        var attempts = new ThreadLocal<RecoveryAttempt>();
        return new DeadLetterPublishingRecoverer(templateResolver, (record, exception) -> {
            TopicPartition destination = destinationResolver.apply(record, exception);
            RecoveryAttempt attempt = attempts.get();
            if (attempt != null) {
                attempt.destination = destination;
            }
            return destination;
        }) {
            @Override
            public void accept(ConsumerRecord<?, ?> record, Consumer<?, ?> consumer, Exception exception) {
                RecoveryAttempt previous = attempts.get();
                var attempt = new RecoveryAttempt();
                attempts.set(attempt);
                try {
                    super.accept(record, consumer, exception);
                } catch (RuntimeException failure) {
                    reportSafely(record, exception, attempt, failure);
                    throw failure;
                } finally {
                    if (previous == null) {
                        attempts.remove();
                    } else {
                        attempts.set(previous);
                    }
                }
                if (attempt.confirmedResult != null) {
                    reportSafely(record, exception, attempt, null);
                }
            }

            @Override
            protected void verifySendResult(
                KafkaOperations<Object, Object> template,
                ProducerRecord<Object, Object> outRecord,
                CompletableFuture<SendResult<Object, Object>> sendResult,
                ConsumerRecord<?, ?> inRecord) {
                RecoveryAttempt attempt = attempts.get();
                if (attempt != null) {
                    attempt.destination = new TopicPartition(outRecord.topic(),
                        outRecord.partition() == null ? -1 : outRecord.partition());
                }
                super.verifySendResult(template, outRecord, sendResult, inRecord);
                if (attempt != null) {
                    attempt.confirmedResult = sendResult;
                }
            }
        };
    }

    private void reportSafely(
        ConsumerRecord<?, ?> record,
        Exception processingFailure,
        RecoveryAttempt attempt,
        RuntimeException handoffFailure) {
        try {
            if (!isNotificationInput(record.topic())) {
                return;
            }
            TopicPartition destination = attempt.destination;
            if (destination == null) {
                recordReportingFailure(record, handoffFailure);
                return;
            }
            boolean dlt = destination.topic().equals(kafkaProperties.getCreateRequestedTopic() + "-dlt");
            if (!dlt && !isRetryTopic(destination.topic())) {
                return;
            }
            if (handoffFailure == null && !dlt) {
                return;
            }

            NotificationFailureCode errorCode = handoffFailure == null
                ? NotificationFailureCode.DLT_RECORDED
                : dlt ? NotificationFailureCode.DLT_PUBLISH_FAILED : NotificationFailureCode.RETRY_PUBLISH_FAILED;
            KafkaLocation destinationLocation = new KafkaLocation(destination.topic(),
                destination.partition() < 0 ? null : destination.partition(), null);
            if (handoffFailure == null) {
                SendResult<Object, Object> result = attempt.confirmedResult.getNow(null);
                RecordMetadata metadata = result.getRecordMetadata();
                destinationLocation = new KafkaLocation(metadata.topic(),
                    metadata.partition() < 0 ? null : metadata.partition(),
                    metadata.hasOffset() ? metadata.offset() : null);
            }

            Throwable processingCause = processingCause(processingFailure);
            KafkaFailureDetails details = KafkaFailureDetails.from(
                consumerGroup(processingFailure),
                originalLocation(record),
                new KafkaLocation(record.topic(), record.partition(), record.offset()),
                destinationLocation,
                currentAttempt(record),
                failureClassifier.isRetryable(processingFailure),
                processingCause);
            NotificationCreateRequested request = record.value() instanceof NotificationCreateRequested value
                ? value
                : null;
            failureReporter.report(NotificationFailureContext.from(
                errorCode, request,
                handoffFailure == null ? processingCause : handoffFailure,
                Instant.now(), details));
        } catch (RuntimeException failure) {
            recordReportingFailure(record, failure);
        }
    }

    private boolean isNotificationInput(String topic) {
        return topic.equals(kafkaProperties.getCreateRequestedTopic()) || isRetryTopic(topic);
    }

    private boolean isRetryTopic(String topic) {
        String prefix = kafkaProperties.getCreateRequestedTopic() + "-retry-";
        return topic.startsWith(prefix) && topic.substring(prefix.length()).matches("[0-9]+");
    }

    private KafkaLocation originalLocation(ConsumerRecord<?, ?> record) {
        if (record.topic().equals(kafkaProperties.getCreateRequestedTopic())) {
            return new KafkaLocation(record.topic(), record.partition(), record.offset());
        }
        Header topic = record.headers().lastHeader(KafkaHeaders.ORIGINAL_TOPIC);
        if (topic == null || topic.value() == null || topic.value().length > 249
            || !new String(topic.value(), StandardCharsets.UTF_8).equals(kafkaProperties.getCreateRequestedTopic())) {
            return null;
        }
        Integer partition = integerHeader(record, KafkaHeaders.ORIGINAL_PARTITION);
        Long offset = longHeader(record, KafkaHeaders.ORIGINAL_OFFSET);
        if (partition != null && partition < 0) {
            partition = null;
        }
        if (offset != null && (offset < 0 || partition == null)) {
            offset = null;
        }
        return new KafkaLocation(kafkaProperties.getCreateRequestedTopic(), partition, offset);
    }

    private Integer currentAttempt(ConsumerRecord<?, ?> record) {
        Header header = record.headers().lastHeader(RetryTopicHeaders.DEFAULT_HEADER_ATTEMPTS);
        if (header == null) {
            return record.topic().equals(kafkaProperties.getCreateRequestedTopic()) ? 1 : null;
        }
        byte[] value = header.value();
        if (value == null) {
            return null;
        }
        Integer attempt;
        if (value.length == Byte.BYTES) {
            attempt = Byte.toUnsignedInt(value[0]);
        } else {
            attempt = integerHeader(record, RetryTopicHeaders.DEFAULT_HEADER_ATTEMPTS);
        }
        return attempt != null && attempt >= 1 && attempt <= Short.MAX_VALUE ? attempt : null;
    }

    private Integer integerHeader(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header != null && header.value() != null && header.value().length == Integer.BYTES
            ? ByteBuffer.wrap(header.value()).getInt()
            : null;
    }

    private Long longHeader(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header != null && header.value() != null && header.value().length == Long.BYTES
            ? ByteBuffer.wrap(header.value()).getLong()
            : null;
    }

    private String consumerGroup(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        while (current != null && visited.add(current)) {
            if (current instanceof ListenerExecutionFailedException listenerFailure
                && listenerFailure.getGroupId() != null) {
                return listenerFailure.getGroupId();
            }
            current = current.getCause();
        }
        return null;
    }

    private Throwable processingCause(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        while (current != null && visited.add(current)
            && (current instanceof ListenerExecutionFailedException
                || current instanceof TimestampedException
                || current instanceof NotificationRetryableCreationException)
            && current.getCause() != null && !visited.contains(current.getCause())) {
            current = current.getCause();
        }
        return current;
    }

    private void recordReportingFailure(ConsumerRecord<?, ?> record, RuntimeException failure) {
        try {
            log.error(
                "errorCode={}, reportOperation=RECOVERY_CONTEXT, inputTopic={}, inputPartition={}, inputOffset={}, exceptionType={}, causeType={}",
                CommonErrorCode.INTERNAL_SERVER_ERROR,
                record.topic(),
                record.partition(),
                record.offset(),
                failure == null ? null : failure.getClass().getSimpleName(),
                failure == null || failure.getCause() == null ? null : failure.getCause().getClass().getSimpleName());
        } catch (RuntimeException ignored) {}
        try {
            meterRegistry.counter("notification.reporting.failures", "operation", "RECOVERY_CONTEXT").increment();
        } catch (RuntimeException ignored) {}
    }

    private static class RecoveryAttempt {

        private TopicPartition destination;
        private CompletableFuture<SendResult<Object, Object>> confirmedResult;
    }
}
