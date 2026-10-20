package com.team.dating_backend.auth.dto;

import java.time.Instant;

public record IssuedAuthTokens(
    String accessToken,
    String refreshToken,
    Instant refreshExpiresAt) {}
