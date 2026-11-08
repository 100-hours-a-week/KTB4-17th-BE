package com.team.dating_backend.aisimulation.dto.event;

import java.time.Instant;

public record AiSimulationRequestedEvent(
    String eventId,
    Long simulationId,
    Instant occurredAt) {}
