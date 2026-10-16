package com.team.dating_backend.file.dto;

public record FileUploadCompletedEvent(
    Long ownerUserId, Long uploadIntentId, Long fileId, String validatedVersionToken) {}
