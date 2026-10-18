package com.team.dating_backend.chat.dto.response;

import com.team.dating_backend.chat.enums.ChatDelegationStatus;
import java.util.List;

public record ChatDelegationDraftResponse(
    Long delegationId,
    ChatDelegationStatus status,
    String draftId,
    List<Turn> turns) {

    public record Turn(int turn, String message) {}
}
