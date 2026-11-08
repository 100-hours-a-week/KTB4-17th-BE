package com.team.dating_backend.common.alert;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.common.exception.BusinessException;

public class DiscordWebhookException extends BusinessException {

    public DiscordWebhookException() {
        super(CommonErrorCode.INTERNAL_SERVER_ERROR);
    }
}
