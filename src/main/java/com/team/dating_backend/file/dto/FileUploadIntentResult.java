package com.team.dating_backend.file.dto;

import java.time.Instant;
import java.util.Map;

public record FileUploadIntentResult(
    Long uploadIntentId,
    String uploadUrl,
    String method,
    Map<String, String> headers,
    Instant uploadUrlExpiresAt,
    Instant intentExpiresAt,
    long maxFileSizeBytes) {}
