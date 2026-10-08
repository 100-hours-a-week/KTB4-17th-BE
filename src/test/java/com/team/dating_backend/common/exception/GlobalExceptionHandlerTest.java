package com.team.dating_backend.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.team.dating_backend.common.dto.response.ErrorResponse;
import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.recommendation.enums.RecommendationPreferenceErrorCode;
import com.team.dating_backend.recommendation.exception.RecommendationPreferenceBusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import tools.jackson.databind.ObjectMapper;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler globalExceptionHandler = new GlobalExceptionHandler();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @ParameterizedTest
    @EnumSource(RecommendationPreferenceErrorCode.class)
    void 선호_조건_오류는_정의한_HTTP_상태와_구체적인_errorCode만_응답한다(
        RecommendationPreferenceErrorCode errorCode) {
        ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleBusinessException(
            new RecommendationPreferenceBusinessException(errorCode));

        assertThat(response.getStatusCode()).isEqualTo(errorCode.status());
        assertThat(objectMapper.writeValueAsString(response.getBody()))
            .isEqualTo("{\"errorCode\":\"" + errorCode.name() + "\"}");
    }

    @Test
    void 비즈니스_예외는_errorCode만_응답한다() {
        // When
        ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleBusinessException(
            new BusinessException(CommonErrorCode.AUTH_REQUIRED) {});

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().errorCode()).isEqualTo(CommonErrorCode.AUTH_REQUIRED.name());
        assertThat(objectMapper.writeValueAsString(response.getBody()))
            .isEqualTo("{\"errorCode\":\"AUTH_REQUIRED\"}");
    }

    @Test
    void 요청_검증_예외는_errorCode만_응답한다() {
        // Given
        RequestValidationException exception = new RequestValidationException();

        // When
        ResponseEntity<ErrorResponse> response = globalExceptionHandler
            .handleBusinessException(exception);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().errorCode())
            .isEqualTo(CommonErrorCode.INVALID_REQUEST.name());
        assertThat(objectMapper.writeValueAsString(response.getBody()))
            .isEqualTo("{\"errorCode\":\"INVALID_REQUEST\"}");
    }

    @Test
    void 내부_예외_메시지는_오류_응답에_포함하지_않는다() {
        // Given
        RequestValidationException exception = new RequestValidationException("유효한 파일 ID가 필요합니다.");

        // When
        ResponseEntity<ErrorResponse> response = globalExceptionHandler
            .handleBusinessException(exception);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().errorCode())
            .isEqualTo(CommonErrorCode.INVALID_REQUEST.name());
        assertThat(objectMapper.writeValueAsString(response.getBody()))
            .isEqualTo("{\"errorCode\":\"INVALID_REQUEST\"}");
    }

    @Test
    void 잘못된_입력은_400과_errorCode만_응답한다() {
        ResponseEntity<ErrorResponse> response = globalExceptionHandler
            .handleInvalidRequest(new HttpMessageNotReadableException(
                "invalid input", new MockHttpInputMessage(new byte[0])));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(objectMapper.writeValueAsString(response.getBody()))
            .isEqualTo("{\"errorCode\":\"INVALID_REQUEST\"}");
    }

    @Test
    void 예상하지_못한_오류는_500과_errorCode만_응답한다() {
        ResponseEntity<ErrorResponse> response = globalExceptionHandler
            .handleUnexpectedException(new IllegalStateException("internal failure"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(objectMapper.writeValueAsString(response.getBody()))
            .isEqualTo("{\"errorCode\":\"INTERNAL_SERVER_ERROR\"}");
    }
}
