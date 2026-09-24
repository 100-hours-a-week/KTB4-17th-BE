package com.team.dating_backend.user.controller;

import com.team.dating_backend.auth.dto.AuthErrorResponse;
import com.team.dating_backend.auth.exception.PendingRegistrationAccessDeniedException;
import io.jsonwebtoken.JwtException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = UserRegistrationController.class)
public class UserRegistrationExceptionHandler {

    @ExceptionHandler({JwtException.class, PendingRegistrationAccessDeniedException.class})
    public ResponseEntity<AuthErrorResponse> handlePendingRegistrationAuthorizationFailure(
        RuntimeException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(new AuthErrorResponse("AUTH_REQUIRED"));
    }
}
