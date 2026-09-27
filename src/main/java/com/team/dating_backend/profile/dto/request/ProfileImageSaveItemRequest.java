package com.team.dating_backend.profile.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ProfileImageSaveItemRequest(
    @NotNull @Positive Long fileId,
    @NotNull Boolean isFrontal) {}
