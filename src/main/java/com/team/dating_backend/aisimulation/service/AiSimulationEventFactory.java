package com.team.dating_backend.aisimulation.service;

import com.team.dating_backend.aisimulation.dto.event.AiSimulationCompletedEvent;
import com.team.dating_backend.aisimulation.dto.event.AiSimulationRequestedEvent;
import com.team.dating_backend.aisimulation.enums.AiSimulationErrorCode;
import com.team.dating_backend.aisimulation.exception.AiSimulationBusinessException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Component;

@Component
public class AiSimulationEventFactory {

    public AiSimulationRequestedEvent requested(Long simulationId, LocalDateTime createdAt) {
        return new AiSimulationRequestedEvent(
            eventId(simulationId, "requested"), simulationId,
            occurredAt(createdAt));
    }

    public AiSimulationCompletedEvent completed(Long simulationId, LocalDateTime finishedAt) {
        return new AiSimulationCompletedEvent(
            eventId(simulationId, "completed"), simulationId,
            occurredAt(finishedAt));
    }

    private String eventId(Long simulationId, String action) {
        if (simulationId == null || simulationId <= 0) {
            throw invalidEvent();
        }
        return "ai-simulation:" + simulationId + ":" + action;
    }

    private Instant occurredAt(LocalDateTime value) {
        if (value == null) {
            throw invalidEvent();
        }
        return value.toInstant(ZoneOffset.UTC);
    }

    private AiSimulationBusinessException invalidEvent() {
        return new AiSimulationBusinessException(AiSimulationErrorCode.SIMULATION_EVENT_INVALID);
    }
}
