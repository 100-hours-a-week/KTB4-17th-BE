package com.team.dating_backend.chat.delegation.repository;

public record ChatDelegationMessageLockTarget(
    Long delegationId,
    Long chatRoomId,
    Long matchId,
    int turn) {}
