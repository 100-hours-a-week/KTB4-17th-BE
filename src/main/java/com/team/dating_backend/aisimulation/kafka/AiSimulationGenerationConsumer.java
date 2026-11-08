package com.team.dating_backend.aisimulation.kafka;

import com.team.dating_backend.aisimulation.dto.event.AiSimulationRequestedEvent;
import com.team.dating_backend.aisimulation.enums.AiSimulationErrorCode;
import com.team.dating_backend.aisimulation.exception.AiSimulationRetryableProcessingException;
import com.team.dating_backend.aisimulation.service.AiSimulationGenerationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AiSimulationGenerationConsumer {

    private final AiSimulationGenerationService generationService;
    private final AiSimulationFailureClassifier failureClassifier;

    @AiSimulationKafkaListeners.Generation
    public void consume(ConsumerRecord<Long, AiSimulationRequestedEvent> record) {
        var event = record.value();
        try {
            generationService.generate(event.simulationId());
        } catch (RuntimeException exception) {
            log.warn(
                "Simulation generation failed: errorCode={}, simulationId={}, eventId={}, topic={}, partition={}, offset={}",
                AiSimulationErrorCode.SIMULATION_PROCESSING_FAILED, event == null ? null : event.simulationId(),
                event == null ? null : event.eventId(), record.topic(), record.partition(), record.offset(), exception);
            if (failureClassifier.isRetryable(exception)
                && !(exception instanceof AiSimulationRetryableProcessingException)) {
                throw new AiSimulationRetryableProcessingException(exception);
            }
            throw exception;
        }
    }
}
