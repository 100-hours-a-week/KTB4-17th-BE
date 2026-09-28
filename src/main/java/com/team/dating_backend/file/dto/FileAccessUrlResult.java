package com.team.dating_backend.file.dto;

import java.time.Instant;

public record FileAccessUrlResult(
    Long fileId,
    String accessUrl,
    String disposition,
    Instant expiresAt) {}
