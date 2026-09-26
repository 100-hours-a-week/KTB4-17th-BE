package com.team.dating_backend.file.dto.response;

import com.team.dating_backend.file.dto.FileUploadIntentResult;
import java.time.Instant;
import java.util.Map;

public record FileUploadIntentResponse(
    Long uploadIntentId,
    String uploadUrl,
    String method,
    Map<String, String> headers,
    Instant uploadUrlExpiresAt,
    Instant intentExpiresAt,
    long maxFileSizeBytes) {

    public static FileUploadIntentResponse from(FileUploadIntentResult result) {
        return new FileUploadIntentResponse(
            result.uploadIntentId(),
            result.uploadUrl(),
            result.method(),
            result.headers(),
            result.uploadUrlExpiresAt(),
            result.intentExpiresAt(),
            result.maxFileSizeBytes());
    }
}
