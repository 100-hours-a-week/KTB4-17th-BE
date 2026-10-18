package com.team.dating_backend.auth.dto;

import java.time.Instant;
import java.util.UUID;

public record RefreshAuthTokenPayload(
    Long userId,
    UUID sessionKey,
    UUID tokenId,
    Instant issuedAt,
    Instant expiresAt) {}
