package com.team.dating_backend.notification.exception;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.common.exception.BusinessException;
import com.team.dating_backend.notification.enums.NotificationErrorCode;

public class NotificationBusinessException extends BusinessException {

    public NotificationBusinessException(NotificationErrorCode errorCode) {
        super(errorCode);
    }

    public NotificationBusinessException(CommonErrorCode errorCode) {
        super(errorCode);
    }
}
