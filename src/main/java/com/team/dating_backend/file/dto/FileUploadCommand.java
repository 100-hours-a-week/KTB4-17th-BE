package com.team.dating_backend.file.dto;

public record FileUploadCommand(Long ownerUserId, UploadFile file) {}
