package com.team.dating_backend.chat.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ChatMessageReadRequest(
    @NotNull @Positive Long lastReadMessageId) {}
