package com.team.dating_backend.notification.exception;

import com.team.dating_backend.common.dto.response.ErrorResponse;
import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.common.enums.ErrorCode;
import com.team.dating_backend.common.exception.BusinessException;
import com.team.dating_backend.notification.controller.NotificationStreamController;
import com.team.dating_backend.notification.sse.NotificationEmitterRegistry;
import com.team.dating_backend.notification.sse.NotificationSseConnection;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.util.DisconnectedClientHelper;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = NotificationStreamController.class)
public class NotificationStreamExceptionHandler {

    private final NotificationEmitterRegistry registry;
    private final ObjectMapper objectMapper;

    @ExceptionHandler(Exception.class)
    public void handleStreamException(
        Exception exception, HttpServletRequest request, HttpServletResponse response) {
        Object attribute = request.getAttribute(NotificationStreamController.CONNECTION_ATTRIBUTE);
        NotificationSseConnection connection = attribute instanceof NotificationSseConnection value ? value : null;
        if (connection != null) {
            registry.remove(connection);
            connection.lockForSend();
        }

        try {
            if (DisconnectedClientHelper.isClientDisconnectedException(exception)) {
                log.debug("SSE request no longer writable. connectionId={}, exceptionType={}",
                    connection == null ? null : connection.getConnectionId(),
                    exception.getClass().getSimpleName());
                return;
            }

            ErrorCode errorCode = exception instanceof BusinessException businessException
                ? businessException.getErrorCode()
                : CommonErrorCode.INTERNAL_SERVER_ERROR;

            if (errorCode.status().is5xxServerError()) {
                log.error("SSE request failed. errorCode={}, connectionId={}, exceptionType={}, causeType={}",
                    errorCode.name(), connection == null ? null : connection.getConnectionId(),
                    exception.getClass().getSimpleName(),
                    exception.getCause() == null ? null : exception.getCause().getClass().getSimpleName());
            }

            if (response.isCommitted()) {
                return;
            }

            writeErrorResponse(errorCode, response);
        } finally {
            if (connection != null) {
                connection.unlockForSend();
            }
            completeInitialAsyncRequest(request);
        }
    }

    private void writeErrorResponse(ErrorCode errorCode, HttpServletResponse response) {
        try {
            byte[] body = objectMapper.writeValueAsBytes(ErrorResponse.of(errorCode));
            response.resetBuffer();
            response.setStatus(errorCode.status().value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache, no-store");
            response.setContentLength(body.length);
            response.getOutputStream().write(body);
            response.flushBuffer();
        } catch (IOException | RuntimeException writeFailure) {
            if (DisconnectedClientHelper.isClientDisconnectedException(writeFailure)) {
                log.debug("SSE error response failed after disconnect. exceptionType={}",
                    writeFailure.getClass().getSimpleName());
                return;
            }

            log.error("SSE error response failed. errorCode={}, exceptionType={}, causeType={}",
                CommonErrorCode.INTERNAL_SERVER_ERROR, writeFailure.getClass().getSimpleName(),
                writeFailure.getCause() == null ? null : writeFailure.getCause().getClass().getSimpleName());
        }
    }

    private void completeInitialAsyncRequest(HttpServletRequest request) {
        if (request.getDispatcherType() != DispatcherType.REQUEST || !request.isAsyncStarted()) {
            return;
        }

        try {
            request.getAsyncContext().complete();
        } catch (IllegalStateException exception) {
            log.debug("SSE initial async completion no longer available. exceptionType={}",
                exception.getClass().getSimpleName());
        }
    }
}
