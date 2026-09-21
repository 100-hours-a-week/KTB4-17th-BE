package com.team.dating_backend.auth.exception;

public class OAuthInvalidRequestException extends RuntimeException {

    public OAuthInvalidRequestException(String message) {
        super(message);
    }
}
