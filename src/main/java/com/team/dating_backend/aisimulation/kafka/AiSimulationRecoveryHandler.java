package com.team.dating_backend.aisimulation.kafka;

import com.team.dating_backend.aisimulation.alert.AiSimulationFailureReporter;
import com.team.dating_backend.aisimulation.config.AiSimulationKafkaProperties;
import com.team.dating_backend.aisimulation.dto.event.AiSimulationRequestedEvent;
import com.team.dating_backend.aisimulation.enums.AiSimulationErrorCode;
import com.team.dating_backend.aisimulation.enums.AiSimulationFailureCode;
import com.team.dating_backend.aisimulation.service.AiSimulationPersistenceService;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AiSimulationRecoveryHandler {

    private final AiSimulationPersistenceService persistence;
    private final AiSimulationKafkaProperties properties;
    private final AiSimulationFailureReporter failureReporter;

    public DeadLetterPublishingRecoverer createRecoverer(
        Function<ProducerRecord<?, ?>, ? extends KafkaOperations<?, ?>> templateResolver,
        BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> destinationResolver) {
        var attempts = new ThreadLocal<RecoveryAttempt>();
        return new DeadLetterPublishingRecoverer(templateResolver, (record, exception) -> {
            TopicPartition destination = destinationResolver.apply(record, exception);
            if (attempts.get() != null) {
                attempts.get().destination = destination;
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
                if (attempt.confirmed != null) {
                    reportSafely(record, exception, attempt, null);
                }
            }

            @Override
            protected void verifySendResult(KafkaOperations<Object, Object> template,
                ProducerRecord<Object, Object> outRecord,
                CompletableFuture<SendResult<Object, Object>> sendResult, ConsumerRecord<?, ?> inRecord) {
                super.verifySendResult(template, outRecord, sendResult, inRecord);
                if (attempts.get() != null) {
                    attempts.get().confirmed = sendResult.getNow(null).getRecordMetadata();
                }
                if (!outRecord.topic().equals(properties.getRequestedTopic() + "-generation-dlt")) {
                    return;
                }
                if ((inRecord.topic().equals(properties.getRequestedTopic())
                    || inRecord.topic().equals(properties.getRequestedTopic() + "-generation-retry"))
                    && inRecord.value() instanceof AiSimulationRequestedEvent event
                    && event.simulationId() != null && event.simulationId() > 0
                    && event.simulationId().equals(inRecord.key()) && event.occurredAt() != null
                    && ("ai-simulation:" + event.simulationId() + ":requested").equals(event.eventId())) {
                    persistence.fail(event.simulationId());
                    return;
                }
                log.warn("Generation DLT has invalid event: errorCode={}, topic={}, partition={}, offset={}",
                    AiSimulationErrorCode.SIMULATION_EVENT_INVALID,
                    inRecord.topic(), inRecord.partition(), inRecord.offset());
            }
        };
    }

    private void reportSafely(ConsumerRecord<?, ?> record, Exception exception, RecoveryAttempt attempt,
        RuntimeException recoveryFailure) {
        try {
            failureReporter.report(record, exception, attempt.destination, attempt.confirmed, recoveryFailure);
        } catch (RuntimeException reportingFailure) {
            log.error("errorCode={}, topic={}, partition={}, offset={}, exceptionType={}",
                AiSimulationFailureCode.REPORTING_FAILED, record.topic(), record.partition(), record.offset(),
                reportingFailure.getClass().getSimpleName());
        }
    }

    private static class RecoveryAttempt {

        private TopicPartition destination;
        private RecordMetadata confirmed;
    }
}
