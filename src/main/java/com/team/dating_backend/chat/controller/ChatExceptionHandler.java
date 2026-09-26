package com.team.dating_backend.chat.controller;

import com.team.dating_backend.chat.enums.ChatErrorCode;
import com.team.dating_backend.chat.exception.ChatBusinessException;
import com.team.dating_backend.common.dto.response.ErrorResponse;
import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.common.enums.ErrorCode;
import com.team.dating_backend.common.exception.RequestValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackageClasses = ChatRoomListController.class)
public class ChatExceptionHandler {

    @ExceptionHandler({MethodArgumentNotValidException.class, RequestValidationException.class})
    public ResponseEntity<ErrorResponse> handleInvalidRequest(Exception exception) {
        return ResponseEntity.status(CommonErrorCode.INVALID_REQUEST.status())
            .body(ErrorResponse.of(CommonErrorCode.INVALID_REQUEST));
    }

    @ExceptionHandler(ChatBusinessException.class)
    public ResponseEntity<ErrorResponse> handleChatBusinessException(
        ChatBusinessException exception) {
        ErrorCode errorCode = exception.getErrorCode();
        if (errorCode.status().is5xxServerError()) {
            log.error("Chat business exception. errorCode={}", errorCode.name(), exception);
        }

        ResponseEntity.BodyBuilder response = ResponseEntity.status(errorCode.status());
        if (errorCode == ChatErrorCode.TOO_MANY_MESSAGE_REQUESTS) {
            response.header(HttpHeaders.RETRY_AFTER, "2");
        }
        return response.body(ErrorResponse.of(errorCode));
    }
}
