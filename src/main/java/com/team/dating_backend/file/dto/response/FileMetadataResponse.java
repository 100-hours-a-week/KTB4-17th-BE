package com.team.dating_backend.file.dto.response;

import com.team.dating_backend.file.dto.FileMetadataResult;
import java.time.LocalDateTime;

public record FileMetadataResponse(
    Long fileId,
    String originalName,
    String mimeType,
    long fileSize,
    LocalDateTime createdAt) {

    public static FileMetadataResponse from(FileMetadataResult result) {
        return new FileMetadataResponse(
            result.fileId(),
            result.originalName(),
            result.mimeType(),
            result.fileSize(),
            result.createdAt());
    }
}
