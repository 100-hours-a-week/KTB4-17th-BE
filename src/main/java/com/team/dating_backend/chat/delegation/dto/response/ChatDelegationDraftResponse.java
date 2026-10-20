package com.team.dating_backend.chat.delegation.dto.response;

import com.team.dating_backend.chat.delegation.enums.ChatDelegationStatus;
import java.util.List;

public record ChatDelegationDraftResponse(
    Long delegationId,
    ChatDelegationStatus status,
    String draftId,
    List<Turn> turns) {

    public record Turn(int turn, String message) {}
}
