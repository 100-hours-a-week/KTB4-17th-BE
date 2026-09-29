package com.team.dating_backend.profile.dto.response;

import java.time.LocalDate;

public record MyProfileResponse(
    String nickname,
    LocalDate birthDate,
    String activityRegionName,
    String profileImageUrl) {}
