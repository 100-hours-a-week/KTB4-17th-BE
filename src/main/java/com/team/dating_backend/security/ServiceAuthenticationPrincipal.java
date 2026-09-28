package com.team.dating_backend.security;

import java.security.Principal;

public record ServiceAuthenticationPrincipal(Long userId) implements Principal {

    @Override
    public String getName() {
        return userId.toString();
    }
}
