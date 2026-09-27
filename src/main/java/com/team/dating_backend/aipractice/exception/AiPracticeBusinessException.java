package com.team.dating_backend.aipractice.exception;

import com.team.dating_backend.aipractice.enums.AiPracticeErrorCode;
import com.team.dating_backend.common.exception.BusinessException;

public class AiPracticeBusinessException extends BusinessException {

    public AiPracticeBusinessException(AiPracticeErrorCode errorCode) {
        super(errorCode);
    }

    public AiPracticeBusinessException(AiPracticeErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }
}
