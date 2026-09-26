package com.team.dating_backend.chat.exception;

import com.team.dating_backend.chat.enums.ChatErrorCode;
import com.team.dating_backend.common.exception.BusinessException;

public class ChatBusinessException extends BusinessException {

    public ChatBusinessException(ChatErrorCode errorCode) {
        super(errorCode);
    }
}
