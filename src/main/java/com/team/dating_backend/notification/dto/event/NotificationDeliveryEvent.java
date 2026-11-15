package com.team.dating_backend.notification.dto.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.team.dating_backend.notification.dto.response.NotificationEventResponse;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NotificationDeliveryEvent(
    String sourceEventId,
    Long recipientUserId,
    NotificationEventResponse notification) {}
