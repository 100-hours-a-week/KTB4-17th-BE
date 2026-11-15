package com.team.dating_backend.aisimulation.kafka;

import com.team.dating_backend.aisimulation.dto.event.AiSimulationCompletedEvent;
import com.team.dating_backend.aisimulation.enums.AiSimulationNotificationRecipient;
import com.team.dating_backend.aisimulation.exception.AiSimulationRetryableProcessingException;
import com.team.dating_backend.aisimulation.service.AiSimulationNotificationProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AiSimulationCompletedNotificationConsumer {

    private final AiSimulationNotificationProcessor processor;
    private final AiSimulationFailureClassifier failureClassifier;

    @AiSimulationKafkaListeners.RequesterNotification
    public void consumeForRequester(ConsumerRecord<Long, AiSimulationCompletedEvent> record) {
        consume(record, AiSimulationNotificationRecipient.REQUESTER);
    }

    @AiSimulationKafkaListeners.TargetNotification
    public void consumeForTarget(ConsumerRecord<Long, AiSimulationCompletedEvent> record) {
        consume(record, AiSimulationNotificationRecipient.TARGET);
    }

    private void consume(ConsumerRecord<Long, AiSimulationCompletedEvent> record,
        AiSimulationNotificationRecipient recipient) {
        var event = record.value();
        try {
            var result = processor.completed(event.simulationId(), recipient);
            log.info(
                "Simulation completion notification: simulationId={}, eventId={}, recipient={}, result={}, topic={}, partition={}, offset={}",
                event.simulationId(), event.eventId(), recipient, result, record.topic(), record.partition(),
                record.offset());
        } catch (RuntimeException exception) {
            log.warn(
                "Simulation completion notification failed: simulationId={}, eventId={}, recipient={}, topic={}, partition={}, offset={}",
                event.simulationId(), event.eventId(), recipient, record.topic(), record.partition(), record.offset(),
                exception);
            if (failureClassifier.isRetryable(exception)
                && !(exception instanceof AiSimulationRetryableProcessingException)) {
                throw new AiSimulationRetryableProcessingException(exception);
            }
            throw exception;
        }
    }
}
