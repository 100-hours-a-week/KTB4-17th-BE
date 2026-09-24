package com.team.dating_backend.auth.dto;

import com.team.dating_backend.auth.enums.AuthProvider;
import com.team.dating_backend.auth.enums.LoginDestination;

public sealed interface SocialLoginResult
    permits SocialLoginResult.Authenticated, SocialLoginResult.PendingRegistration {

    record Authenticated(Long userId, LoginDestination destination) implements SocialLoginResult {}

    record PendingRegistration(AuthProvider provider, String providerUserId)
        implements
            SocialLoginResult {}
}
