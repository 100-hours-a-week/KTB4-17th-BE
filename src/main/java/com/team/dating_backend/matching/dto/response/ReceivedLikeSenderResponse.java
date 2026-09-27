package com.team.dating_backend.matching.dto.response;

public record ReceivedLikeSenderResponse(
    Long memberId,
    String nickname,
    String profileImageUrl,
    Integer age,
    String job,
    String region) {}
