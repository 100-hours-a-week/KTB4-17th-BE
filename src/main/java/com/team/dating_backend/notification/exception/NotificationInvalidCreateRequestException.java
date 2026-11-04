package com.team.dating_backend.notification.exception;

import com.team.dating_backend.common.enums.CommonErrorCode;

public class NotificationInvalidCreateRequestException extends NotificationBusinessException {

    public NotificationInvalidCreateRequestException() {
        super(CommonErrorCode.INTERNAL_SERVER_ERROR);
    }
}
