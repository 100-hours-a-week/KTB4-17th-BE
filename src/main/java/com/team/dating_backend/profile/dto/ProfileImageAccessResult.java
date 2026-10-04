package com.team.dating_backend.profile.dto;

import java.time.Instant;

public record ProfileImageAccessResult(
    Long fileId,
    short displayOrder,
    String imageUrl,
    Instant expiresAt) {}
