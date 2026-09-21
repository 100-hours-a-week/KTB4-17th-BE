package com.team.dating_backend.auth.service;

import com.team.dating_backend.auth.dto.OAuthIdentity;
import com.team.dating_backend.auth.enums.AuthProvider;

public interface OAuthProviderClient {

    AuthProvider provider();

    String createAuthorizationUrl(String state);

    OAuthIdentity requestIdentity(String authorizationCode);
}
