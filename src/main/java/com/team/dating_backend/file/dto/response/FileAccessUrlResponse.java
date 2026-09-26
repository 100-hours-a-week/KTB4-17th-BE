package com.team.dating_backend.file.dto.response;

import com.team.dating_backend.file.dto.FileAccessUrlResult;
import java.time.Instant;

public record FileAccessUrlResponse(
    Long fileId,
    String accessUrl,
    String disposition,
    Instant expiresAt) {

    public static FileAccessUrlResponse from(FileAccessUrlResult result) {
        return new FileAccessUrlResponse(
            result.fileId(),
            result.accessUrl(),
            result.disposition(),
            result.expiresAt());
    }
}
