package com.team.dating_backend.profiletrust.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateFaceVerificationRequest(@NotNull @Positive Long uploadIntentId) {}
