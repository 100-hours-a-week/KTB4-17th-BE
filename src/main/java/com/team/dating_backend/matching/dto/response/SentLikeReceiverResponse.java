package com.team.dating_backend.matching.dto.response;

public record SentLikeReceiverResponse(
    Long userId,
    String nickname,
    String profileImageUrl,
    Integer age,
    String job,
    String region) {}
