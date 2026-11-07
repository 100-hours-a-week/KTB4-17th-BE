package com.team.dating_backend.aisimulation.kafka;

import com.team.dating_backend.aisimulation.dto.event.AiSimulationRequestedEvent;
import com.team.dating_backend.aisimulation.service.AiSimulationGenerationService;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AiSimulationGenerationConsumer {

    private final AiSimulationGenerationService generationService;

    @AiSimulationKafkaListeners.Generation
    public void consume(ConsumerRecord<Long, AiSimulationRequestedEvent> record) {
        generationService.generate(record.value().simulationId());
    }
}
