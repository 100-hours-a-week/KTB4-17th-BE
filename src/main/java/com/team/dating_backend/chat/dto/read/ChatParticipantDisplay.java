package com.team.dating_backend.chat.dto.read;

public record ChatParticipantDisplay(
    Long userId,
    String nickname,
    String profileImageUrl) {}
