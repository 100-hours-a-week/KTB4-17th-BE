package com.team.dating_backend.chat.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.team.dating_backend.chat.enums.ChatErrorCode;
import com.team.dating_backend.chat.exception.ChatBusinessException;
import com.team.dating_backend.common.dto.response.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class ChatExceptionHandlerTest {

    private final ChatExceptionHandler handler = new ChatExceptionHandler();

    @Test
    void 메시지_요청_제한_초과는_429와_RetryAfter와_errorCode를_반환한다() {
        ResponseEntity<ErrorResponse> response = handler.handleChatBusinessException(
            new ChatBusinessException(ChatErrorCode.TOO_MANY_MESSAGE_REQUESTS));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("2");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode())
            .isEqualTo(ChatErrorCode.TOO_MANY_MESSAGE_REQUESTS.name());
        assertThat(response.getBody().errors()).isEmpty();
    }

    @Test
    void 다른_채팅_오류에는_RetryAfter를_추가하지_않는다() {
        ResponseEntity<ErrorResponse> response = handler.handleChatBusinessException(
            new ChatBusinessException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNull();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode())
            .isEqualTo(ChatErrorCode.CHAT_ROOM_NOT_FOUND.name());
    }
}
