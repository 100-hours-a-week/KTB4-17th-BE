package com.team.dating_backend.profile.dto;

import com.team.dating_backend.profile.dto.response.UserProfileResponse;
import java.time.Instant;

public record UserProfileLoadResult(
    UserProfileResponse profile,
    Instant loadedAt,
    Instant earliestImageExpiresAt) {}
