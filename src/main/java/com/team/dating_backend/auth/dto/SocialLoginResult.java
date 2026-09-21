package com.team.dating_backend.auth.dto;

import com.team.dating_backend.auth.enums.AuthProvider;
import com.team.dating_backend.auth.enums.LoginDestination;

public sealed interface SocialLoginResult
        permits SocialLoginResult.Authenticated, SocialLoginResult.PendingOnboarding {

    record Authenticated(Long userId, LoginDestination destination) implements SocialLoginResult {}

    record PendingOnboarding(AuthProvider provider, String providerUserId)
            implements SocialLoginResult {}
}
