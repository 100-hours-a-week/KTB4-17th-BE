package com.team.dating_backend.aisimulation.kafka;

import com.team.dating_backend.aisimulation.dto.event.AiSimulationRequestedEvent;
import com.team.dating_backend.aisimulation.exception.AiSimulationRetryableProcessingException;
import com.team.dating_backend.aisimulation.service.AiSimulationNotificationProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AiSimulationRequestNotificationConsumer {

    private final AiSimulationNotificationProcessor processor;
    private final AiSimulationFailureClassifier failureClassifier;

    @AiSimulationKafkaListeners.RequestNotification
    public void consume(ConsumerRecord<Long, AiSimulationRequestedEvent> record) {
        var event = record.value();
        try {
            var result = processor.requested(event.simulationId());
            log.info(
                "Simulation request notification: simulationId={}, eventId={}, result={}, topic={}, partition={}, offset={}",
                event.simulationId(), event.eventId(), result, record.topic(), record.partition(), record.offset());
        } catch (RuntimeException exception) {
            log.warn(
                "Simulation request notification failed: simulationId={}, eventId={}, topic={}, partition={}, offset={}",
                event.simulationId(), event.eventId(), record.topic(), record.partition(), record.offset(), exception);
            if (failureClassifier.isRetryable(exception)
                && !(exception instanceof AiSimulationRetryableProcessingException)) {
                throw new AiSimulationRetryableProcessingException(exception);
            }
            throw exception;
        }
    }
}
