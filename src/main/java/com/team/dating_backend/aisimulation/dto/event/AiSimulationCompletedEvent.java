package com.team.dating_backend.aisimulation.dto.event;

import java.time.Instant;

public record AiSimulationCompletedEvent(
    String eventId,
    Long simulationId,
    Instant occurredAt) {}
