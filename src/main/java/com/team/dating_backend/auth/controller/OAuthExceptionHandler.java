package com.team.dating_backend.auth.controller;

import com.team.dating_backend.auth.dto.AuthErrorResponse;
import com.team.dating_backend.auth.exception.KakaoTalkNicknameRequiredException;
import com.team.dating_backend.auth.exception.KakaoTalkNicknameTooLongException;
import com.team.dating_backend.auth.exception.OAuthInvalidRequestException;
import com.team.dating_backend.auth.exception.OAuthProviderUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = OAuthController.class)
public class OAuthExceptionHandler {

    @ExceptionHandler(OAuthInvalidRequestException.class)
    public ResponseEntity<AuthErrorResponse> handleInvalidRequest(
        OAuthInvalidRequestException exception) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
    }

    @ExceptionHandler(OAuthProviderUnavailableException.class)
    public ResponseEntity<AuthErrorResponse> handleProviderUnavailable(
        OAuthProviderUnavailableException exception) {
        return error(HttpStatus.BAD_GATEWAY, "AUTH_PROVIDER_UNAVAILABLE");
    }

    @ExceptionHandler(KakaoTalkNicknameRequiredException.class)
    public ResponseEntity<AuthErrorResponse> handleKakaoTalkNicknameRequired(
        KakaoTalkNicknameRequiredException exception) {
        return error(HttpStatus.BAD_REQUEST, "KAKAO_TALK_NICKNAME_REQUIRED");
    }

    @ExceptionHandler(KakaoTalkNicknameTooLongException.class)
    public ResponseEntity<AuthErrorResponse> handleKakaoTalkNicknameTooLong(
        KakaoTalkNicknameTooLongException exception) {
        return error(HttpStatus.BAD_REQUEST, "KAKAO_TALK_NICKNAME_TOO_LONG");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<AuthErrorResponse> handleUnexpected(Exception exception) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR");
    }

    private ResponseEntity<AuthErrorResponse> error(HttpStatus status, String errorCode) {
        return ResponseEntity.status(status).body(new AuthErrorResponse(errorCode));
    }
}
