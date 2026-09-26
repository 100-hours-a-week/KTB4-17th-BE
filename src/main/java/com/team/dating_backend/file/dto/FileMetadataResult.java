package com.team.dating_backend.file.dto;

import java.time.LocalDateTime;

public record FileMetadataResult(
    Long fileId,
    String originalName,
    String mimeType,
    long fileSize,
    LocalDateTime createdAt) {}
