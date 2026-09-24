package com.team.dating_backend.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.team.dating_backend.common.dto.response.ErrorResponse;
import com.team.dating_backend.common.dto.response.FieldErrorResponse;
import com.team.dating_backend.common.enums.CommonErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler globalExceptionHandler = new GlobalExceptionHandler();

    @Test
    void 비즈니스_예외는_errorCode만_응답한다() {
        // When
        ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleBusinessException(
            new BusinessException(CommonErrorCode.AUTH_REQUIRED) {});

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().errorCode()).isEqualTo(CommonErrorCode.AUTH_REQUIRED.name());
        assertThat(response.getBody().errors()).isEmpty();
    }

    @Test
    void 요청_검증_예외는_errorCode와_errors를_응답한다() {
        // Given
        RequestValidationException exception = new RequestValidationException(
            List.of(new FieldErrorResponse("image", "이미지를 선택해주세요.")));

        // When
        ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleRequestValidation(exception);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().errorCode())
            .isEqualTo(CommonErrorCode.INVALID_REQUEST.name());
        assertThat(response.getBody().errors())
            .containsExactly(new FieldErrorResponse("image", "이미지를 선택해주세요."));
    }

    @Test
    void 요청_검증_예외에_필드_오류가_없으면_errorCode만_응답한다() {
        // Given
        RequestValidationException exception = new RequestValidationException("유효한 파일 ID가 필요합니다.");

        // When
        ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleRequestValidation(exception);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().errorCode())
            .isEqualTo(CommonErrorCode.INVALID_REQUEST.name());
        assertThat(response.getBody().errors()).isEmpty();
    }
}
