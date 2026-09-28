package com.team.dating_backend.aisimulation.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record AiSimulationCreateRequest(
    @NotNull @Positive Long targetMemberId) {}
