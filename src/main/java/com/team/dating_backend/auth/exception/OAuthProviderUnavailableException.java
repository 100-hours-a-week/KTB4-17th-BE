package com.team.dating_backend.auth.exception;

public class OAuthProviderUnavailableException extends RuntimeException {

    public OAuthProviderUnavailableException() {
        super();
    }

    public OAuthProviderUnavailableException(Throwable cause) {
        super(cause);
    }
}
