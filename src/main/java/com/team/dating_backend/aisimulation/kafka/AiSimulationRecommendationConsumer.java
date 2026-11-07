package com.team.dating_backend.aisimulation.kafka;

import com.team.dating_backend.aisimulation.dto.event.AiSimulationRequestedEvent;
import com.team.dating_backend.aisimulation.service.AiSimulationRecommendationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AiSimulationRecommendationConsumer {

    private final AiSimulationRecommendationService recommendations;

    @AiSimulationKafkaListeners.Recommendation
    public void consume(ConsumerRecord<Long, AiSimulationRequestedEvent> record) {
        var event = record.value();
        try {
            var result = recommendations.addRequester(event.simulationId());
            log.info(
                "Simulation recommendation: simulationId={}, eventId={}, result={}, topic={}, partition={}, offset={}",
                event.simulationId(), event.eventId(), result, record.topic(), record.partition(), record.offset());
        } catch (RuntimeException exception) {
            log.warn(
                "Simulation recommendation failed: simulationId={}, eventId={}, topic={}, partition={}, offset={}",
                event.simulationId(), event.eventId(), record.topic(), record.partition(), record.offset(), exception);
            throw exception;
        }
    }
}
