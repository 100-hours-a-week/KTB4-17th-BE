package com.team.dating_backend.aisimulation.kafka;

import com.team.dating_backend.aisimulation.config.AiSimulationKafkaProperties;
import com.team.dating_backend.aisimulation.dto.event.AiSimulationRequestedEvent;
import com.team.dating_backend.aisimulation.enums.AiSimulationErrorCode;
import com.team.dating_backend.aisimulation.service.AiSimulationPersistenceService;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
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

    public DeadLetterPublishingRecoverer createRecoverer(
        Function<ProducerRecord<?, ?>, ? extends KafkaOperations<?, ?>> templateResolver,
        BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> destinationResolver) {
        return new DeadLetterPublishingRecoverer(templateResolver, destinationResolver) {
            @Override
            protected void verifySendResult(KafkaOperations<Object, Object> template,
                ProducerRecord<Object, Object> outRecord,
                CompletableFuture<SendResult<Object, Object>> sendResult, ConsumerRecord<?, ?> inRecord) {
                super.verifySendResult(template, outRecord, sendResult, inRecord);
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
}
