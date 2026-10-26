package com.team.dating_backend.chat.delegation.dto.response;

import com.team.dating_backend.chat.delegation.enums.ChatDelegationEndType;
import com.team.dating_backend.chat.delegation.enums.ChatDelegationStatus;

public record ChatDelegationSummary(
    Long delegationId,
    ChatDelegationStatus status,
    ChatDelegationEndType endType) {}
