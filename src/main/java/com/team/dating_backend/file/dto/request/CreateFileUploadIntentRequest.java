package com.team.dating_backend.file.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateFileUploadIntentRequest(
    @NotBlank @Size(max = 255) String originalName,
    @NotBlank @Size(max = 100) String contentType) {}
