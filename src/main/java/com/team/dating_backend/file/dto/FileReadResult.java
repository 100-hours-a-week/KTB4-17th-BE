package com.team.dating_backend.file.dto;

public record FileReadResult(
    Long fileId, String originalName, String mimeType, long fileSize, String readUrl) {}
