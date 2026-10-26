package com.team.dating_backend.chat.delegation.dto.request;

import com.team.dating_backend.chat.delegation.enums.ChatDelegationEndType;
import jakarta.validation.constraints.NotNull;

public record ChatDelegationCreateRequest(@NotNull ChatDelegationEndType endType) {}
