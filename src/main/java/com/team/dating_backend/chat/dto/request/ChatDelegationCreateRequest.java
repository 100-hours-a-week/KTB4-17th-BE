package com.team.dating_backend.chat.dto.request;

import com.team.dating_backend.chat.enums.ChatDelegationEndType;
import jakarta.validation.constraints.NotNull;

public record ChatDelegationCreateRequest(@NotNull ChatDelegationEndType endType) {}
