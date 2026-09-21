package com.team.dating_backend.user.controller;

import com.team.dating_backend.auth.dto.AuthErrorResponse;
import com.team.dating_backend.auth.exception.PendingOnboardingAccessDeniedException;
import io.jsonwebtoken.JwtException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = OnboardingController.class)
public class OnboardingExceptionHandler {

    @ExceptionHandler({JwtException.class, PendingOnboardingAccessDeniedException.class})
    public ResponseEntity<AuthErrorResponse> handlePendingOnboardingAuthorizationFailure(
            RuntimeException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new AuthErrorResponse("AUTH_REQUIRED"));
    }
}
