package com.team.dating_backend.notification.sse;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.config.NotificationDeliveryProperties;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import java.util.UUID;
import lombok.Getter;
import org.springframework.stereotype.Component;

@Getter
@Component
public class NotificationSseProcessIdentity {

    private final String processId;
    private final String groupId;

    public NotificationSseProcessIdentity(NotificationDeliveryProperties properties) {
        String prefix = properties == null ? null : properties.getGroupIdPrefix();
        if (prefix == null || prefix.isBlank()) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }

        this.processId = UUID.randomUUID().toString();
        this.groupId = prefix + "-" + processId;
    }
}
