package com.team.dating_backend.notification.exception;

import com.team.dating_backend.common.enums.CommonErrorCode;

public class NotificationRetryableCreationException extends NotificationBusinessException {

    public NotificationRetryableCreationException(Throwable cause) {
        super(CommonErrorCode.INTERNAL_SERVER_ERROR, cause);
    }
}
