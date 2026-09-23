package com.team.dating_backend.file.dto;

public record UploadFile(String originalName, String mimeType, byte[] content) {}
