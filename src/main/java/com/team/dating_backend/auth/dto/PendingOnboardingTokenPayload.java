package com.team.dating_backend.auth.dto;

import com.team.dating_backend.auth.enums.AuthProvider;

public record PendingOnboardingTokenPayload(AuthProvider provider, String providerUserId) {}
