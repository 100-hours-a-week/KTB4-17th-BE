package com.team.dating_backend.persona.kakao.exception;

import com.team.dating_backend.common.exception.BusinessException;
import com.team.dating_backend.persona.kakao.enums.KakaoConversationErrorCode;

public class KakaoConversationBusinessException extends BusinessException {

    public KakaoConversationBusinessException(KakaoConversationErrorCode errorCode) {
        super(errorCode);
    }

    public KakaoConversationBusinessException(KakaoConversationErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }
}
