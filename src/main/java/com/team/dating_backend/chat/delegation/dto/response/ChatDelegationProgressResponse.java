package com.team.dating_backend.chat.delegation.dto.response;

import com.team.dating_backend.chat.delegation.enums.ChatDelegationStatus;
import java.time.LocalDateTime;
import java.util.List;

public record ChatDelegationProgressResponse(
    Long delegationId,
    ChatDelegationStatus status,
    List<TurnProgress> turns) {

    public record TurnProgress(int turn, boolean completed, LocalDateTime sentAt) {}
}
