package com.team.dating_backend.auth.service;

import com.team.dating_backend.auth.enums.AuthProvider;
import com.team.dating_backend.auth.exception.OAuthInvalidRequestException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class OAuthProviderClientRegistry {

    private final Map<AuthProvider, OAuthProviderClient> clients =
            new EnumMap<>(AuthProvider.class);

    public OAuthProviderClientRegistry(List<OAuthProviderClient> providerClients) {
        for (OAuthProviderClient providerClient : providerClients) {
            OAuthProviderClient existingClient =
                    clients.put(providerClient.provider(), providerClient);

            if (existingClient != null) {
                throw new IllegalStateException(
                        "Duplicate OAuth provider client: " + providerClient.provider());
            }
        }
    }

    public OAuthProviderClient get(AuthProvider provider) {
        OAuthProviderClient providerClient = clients.get(provider);

        if (providerClient == null) {
            throw new OAuthInvalidRequestException("Unsupported authentication provider");
        }

        return providerClient;
    }
}
