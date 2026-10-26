package com.team.dating_backend.notification.dto.event;

import com.team.dating_backend.notification.dto.response.NotificationEventResponse;

public record NotificationDeliveryEvent(
    int schemaVersion,
    String sourceEventId,
    Long recipientUserId,
    NotificationEventResponse notification) {

    public static final int CURRENT_SCHEMA_VERSION = 1;
}
