package com.team.dating_backend.chat.delegation.dto.response;

import com.team.dating_backend.chat.delegation.enums.ChatDelegationStatus;

public record ChatDelegationStartResponse(
    Long delegationId,
    ChatDelegationStatus status) {}
