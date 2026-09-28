package com.team.dating_backend.auth.dto.response;

public record AuthTokenResponse(String accessToken, String tokenType) {

    public static AuthTokenResponse bearer(String accessToken) {
        return new AuthTokenResponse(accessToken, "Bearer");
    }
}
