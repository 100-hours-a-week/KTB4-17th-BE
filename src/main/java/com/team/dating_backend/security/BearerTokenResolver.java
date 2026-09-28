package com.team.dating_backend.security;

import org.springframework.util.StringUtils;

public final class BearerTokenResolver {

    private static final String BEARER_PREFIX = "Bearer ";

    private BearerTokenResolver() {}

    public static String resolve(String authorizationHeader) {
        if (!StringUtils.hasText(authorizationHeader)
            || !authorizationHeader.regionMatches(
                true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }

        String token = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        return StringUtils.hasText(token) ? token : null;
    }
}
