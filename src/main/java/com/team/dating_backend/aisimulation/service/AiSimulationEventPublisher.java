package com.team.dating_backend.aisimulation.service;

import com.team.dating_backend.aisimulation.config.AiSimulationKafkaProperties;
import com.team.dating_backend.aisimulation.enums.AiSimulationErrorCode;
import com.team.dating_backend.aisimulation.exception.AiSimulationBusinessException;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AiSimulationEventPublisher {

    private final KafkaTemplate<Object, Object> kafkaTemplate;
    private final AiSimulationKafkaProperties properties;
    private final AiSimulationEventFactory eventFactory;

    public void publishRequested(Long simulationId, LocalDateTime createdAt) {
        var event = eventFactory.requested(simulationId, createdAt);
        publish(properties.getRequestedTopic(), simulationId, event);
    }

    public void publishCompleted(Long simulationId, LocalDateTime finishedAt) {
        var event = eventFactory.completed(simulationId, finishedAt);
        publish(properties.getCompletedTopic(), simulationId, event);
    }

    private void publish(String topic, Long simulationId, Object event) {
        try {
            kafkaTemplate.send(topic, simulationId, event)
                .get(properties.getPublishResultTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw publishFailed(exception);
        } catch (ExecutionException | TimeoutException | RuntimeException exception) {
            throw publishFailed(exception);
        }
    }

    private AiSimulationBusinessException publishFailed(Exception cause) {
        return new AiSimulationBusinessException(AiSimulationErrorCode.SIMULATION_EVENT_PUBLISH_FAILED, cause);
    }
}
