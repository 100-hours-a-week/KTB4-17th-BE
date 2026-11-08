package com.team.dating_backend.aisimulation.alert;

import com.team.dating_backend.aisimulation.config.AiSimulationAlertProperties;
import com.team.dating_backend.aisimulation.config.AiSimulationKafkaProperties;
import com.team.dating_backend.aisimulation.dto.event.AiSimulationCompletedEvent;
import com.team.dating_backend.aisimulation.dto.event.AiSimulationRequestedEvent;
import com.team.dating_backend.aisimulation.enums.AiSimulationFailureCode;
import com.team.dating_backend.aisimulation.enums.AiSimulationErrorCode;
import com.team.dating_backend.common.alert.DiscordWebhookSender;
import com.team.dating_backend.common.exception.BusinessException;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.retrytopic.RetryTopicHeaders;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AiSimulationFailureReporter {

    private final AiSimulationAlertProperties properties;
    private final AiSimulationKafkaProperties kafkaProperties;
    private final Executor executor;
    private final DiscordWebhookSender sender;
    private final MeterRegistry registry;
    private final ArrayDeque<Long> attempts = new ArrayDeque<>();

    public AiSimulationFailureReporter(AiSimulationAlertProperties properties,
        AiSimulationKafkaProperties kafkaProperties,
        @Qualifier("aiSimulationAlertExecutor") Executor executor,
        @Qualifier("aiSimulationDiscordWebhookSender") DiscordWebhookSender sender, MeterRegistry registry) {
        this.properties = properties;
        this.kafkaProperties = kafkaProperties;
        this.executor = executor;
        this.sender = sender;
        this.registry = registry;
    }

    public void report(ConsumerRecord<?, ?> record, Exception processingFailure, TopicPartition destination,
        RecordMetadata confirmed, RuntimeException recoveryFailure) {
        boolean dlt = destination != null && destination.topic().endsWith("-dlt");
        if (recoveryFailure == null && (!dlt || confirmed == null)) {
            return;
        }
        AiSimulationFailureCode code = recoveryFailure == null
            ? AiSimulationFailureCode.DLT_RECORDED
            : destination == null
                ? AiSimulationFailureCode.RECOVERY_FAILED
                : confirmed != null
                    ? AiSimulationFailureCode.FINAL_STATE_UPDATE_FAILED
                    : dlt ? AiSimulationFailureCode.DLT_PUBLISH_FAILED : AiSimulationFailureCode.RETRY_PUBLISH_FAILED;
        Map<String, Object> fields = new LinkedHashMap<>();
        try {
            fields.put("environment", properties.getEnvironment());
            fields.put("errorCode", code);
            fields.put("task", task(destination));
            fields.put("eventId", record.value() instanceof AiSimulationRequestedEvent event
                ? event.eventId()
                : record.value() instanceof AiSimulationCompletedEvent event ? event.eventId() : null);
            fields.put("simulationId", record.value() instanceof AiSimulationRequestedEvent event
                ? event.simulationId()
                : record.value() instanceof AiSimulationCompletedEvent event ? event.simulationId() : null);
            fields.put("eventOccurredAt",
                record.value() instanceof AiSimulationRequestedEvent event
                    ? event.occurredAt()
                    : record.value() instanceof AiSimulationCompletedEvent event ? event.occurredAt() : null);
            fields.put("occurredAt", Instant.now());
            fields.put("inputTopic", record.topic());
            fields.put("inputPartition", record.partition());
            fields.put("inputOffset", record.offset());
            fields.put("destinationTopic", destination == null ? null : destination.topic());
            fields.put("destinationPartition", destination == null || destination.partition() < 0
                ? null
                : destination.partition());
            fields.put("destinationOffset", confirmed != null && confirmed.hasOffset() ? confirmed.offset() : null);
            fields.put("attempt", attempt(record));
            fields.put("consumerGroup", null);
            fields.put("processingErrorCode", null);
            fields.put("processingExceptionType", processingFailure.getClass().getSimpleName());
            Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Throwable cause = processingFailure; cause != null && visited.add(cause); cause = cause.getCause()) {
                if (cause instanceof ListenerExecutionFailedException listener) {
                    fields.put("consumerGroup", listener.getGroupId());
                }
                if (cause instanceof BusinessException business) {
                    fields.put("processingErrorCode", business.getErrorCode());
                }
                fields.put("processingCauseType", cause.getClass().getSimpleName());
            }
            if ("GENERATION".equals(fields.get("task"))
                && AiSimulationErrorCode.SIMULATION_EVENT_PUBLISH_FAILED.equals(fields.get("processingErrorCode"))) {
                fields.put("task", "COMPLETION_PUBLISH");
            }
            fields.put("exceptionType", (recoveryFailure == null ? processingFailure : recoveryFailure)
                .getClass().getSimpleName());
            fields.replaceAll((name, entry) -> entry instanceof String text ? clean(text) : entry);
            safely(() -> log.error("AI simulation recovery: {}", fields), fields);
            safely(() -> registry.counter("ai.simulation.failures", "error_code", code.name(),
                "task", fields.get("task").toString()).increment(), fields);
            if (code == AiSimulationFailureCode.DLT_RECORDED && properties.isEnabled()
                && !"UNKNOWN".equals(fields.get("task"))) {
                executor.execute(() -> send(fields));
            }
        } catch (RejectedExecutionException exception) {
            recordFailure(AiSimulationFailureCode.ALERT_SUBMISSION_REJECTED, fields, exception);
        } catch (RuntimeException exception) {
            recordFailure(AiSimulationFailureCode.REPORTING_FAILED, fields, exception);
        }
    }

    private String task(TopicPartition destination) {
        if (destination != null) {
            for (String task : Set.of("generation", "request-notification", "recommendation",
                "requester-notification", "target-notification")) {
                String topic = (task.startsWith("requester-") || task.startsWith("target-")
                    ? kafkaProperties.getCompletedTopic()
                    : kafkaProperties.getRequestedTopic()) + "-" + task;
                if (destination.topic().equals(topic + "-retry") || destination.topic().equals(topic + "-dlt")) {
                    return task.replace('-', '_').toUpperCase(Locale.ROOT);
                }
            }
        }
        return "UNKNOWN";
    }

    private String clean(String value) {
        String text = value.replaceAll("[\\p{Cntrl}\\p{Cf}\\p{Zl}\\p{Zp}]", " ");
        return text.substring(0, text.offsetByCodePoints(0, Math.min(text.codePointCount(0, text.length()), 249)));
    }

    private Integer attempt(ConsumerRecord<?, ?> record) {
        var header = record.headers().lastHeader(RetryTopicHeaders.DEFAULT_HEADER_ATTEMPTS);
        if (header == null) {
            return record.topic().equals(kafkaProperties.getRequestedTopic())
                || record.topic().equals(kafkaProperties.getCompletedTopic()) ? 1 : null;
        }
        byte[] value = header.value();
        Integer attempt = value != null && value.length == 1
            ? Byte.toUnsignedInt(value[0])
            : value != null && value.length == Integer.BYTES ? ByteBuffer.wrap(value).getInt() : null;
        return attempt != null && attempt > 0 && attempt <= Short.MAX_VALUE ? attempt : null;
    }

    private void send(Map<String, Object> fields) {
        try {
            if (!properties.isEnabled()) {
                return;
            }
            if (!tryAcquire()) {
                safely(() -> registry.counter("ai.simulation.alert.suppressed", "reason", "rate_limit").increment(),
                    fields);
                return;
            }
            sender.send(properties.getWebhookUrl(), "AI 시뮬레이션 처리 실패", fields);
            safely(() -> registry.counter("ai.simulation.alert.sent").increment(), fields);
        } catch (RuntimeException exception) {
            recordFailure(AiSimulationFailureCode.ALERT_WEBHOOK_FAILED, fields, exception);
        }
    }

    private synchronized boolean tryAcquire() {
        long now = registry.config().clock().monotonicTime();
        long window = TimeUnit.MILLISECONDS.toNanos(properties.getRateLimitWindowMs());
        while (!attempts.isEmpty() && now - attempts.peekFirst() >= window) {
            attempts.removeFirst();
        }
        if (attempts.size() >= properties.getMaxMessagesPerWindow()) {
            return false;
        }
        attempts.addLast(now);
        return true;
    }

    private void recordFailure(AiSimulationFailureCode code, Map<String, Object> fields, RuntimeException exception) {
        try {
            log.error("errorCode={}, originalErrorCode={}, task={}, exceptionType={}", code, fields.get("errorCode"),
                fields.get("task"), exception.getClass().getSimpleName());
        } catch (RuntimeException reportingFailure) {
            if (reportingFailure != exception) {
                exception.addSuppressed(reportingFailure);
            }
        }
        try {
            registry.counter("ai.simulation.alert.failures", "error_code", code.name()).increment();
        } catch (RuntimeException reportingFailure) {
            if (reportingFailure != exception) {
                exception.addSuppressed(reportingFailure);
            }
        }
    }

    private void safely(Runnable action, Map<String, Object> fields) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            recordFailure(AiSimulationFailureCode.REPORTING_FAILED, fields, exception);
        }
    }
}
