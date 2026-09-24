package com.team.dating_backend.matching.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record LikeCreateRequest(@NotNull @Positive Long receiverId) {}
