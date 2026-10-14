package com.team.dating_backend.matching.dto.response;

import com.team.dating_backend.profile.enums.JobCategory;

public record ReceivedLikeSenderResponse(
    Long userId,
    String nickname,
    String profileImageUrl,
    Integer age,
    JobCategory job,
    String region) {}
