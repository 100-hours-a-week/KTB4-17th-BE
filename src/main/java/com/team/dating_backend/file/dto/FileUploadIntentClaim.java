package com.team.dating_backend.file.dto;

import com.team.dating_backend.file.entity.FileUploadIntent;

public record FileUploadIntentClaim(FileUploadIntent intent, boolean acquired) {}
