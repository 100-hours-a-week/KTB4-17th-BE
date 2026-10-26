package com.team.dating_backend.notification.exception;

import com.team.dating_backend.common.enums.CommonErrorCode;

public class NotificationInvalidDeliveryEventException extends NotificationBusinessException {

    public NotificationInvalidDeliveryEventException() {
        super(CommonErrorCode.INTERNAL_SERVER_ERROR);
    }

    public NotificationInvalidDeliveryEventException(Throwable cause) {
        super(CommonErrorCode.INTERNAL_SERVER_ERROR, cause);
    }
}
