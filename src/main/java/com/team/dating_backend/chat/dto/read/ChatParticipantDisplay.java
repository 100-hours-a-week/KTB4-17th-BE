package com.team.dating_backend.chat.dto.read;

public record ChatParticipantDisplay(
    Long memberId,
    String nickname,
    String profileImageUrl) {}
