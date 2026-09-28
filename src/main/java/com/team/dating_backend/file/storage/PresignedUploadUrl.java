package com.team.dating_backend.file.storage;

import java.time.Instant;

public record PresignedUploadUrl(String url, Instant expiresAt) {}
