package com.team.dating_backend.file.storage;

import java.time.Instant;

public record PresignedReadUrl(String url, Instant expiresAt) {}
