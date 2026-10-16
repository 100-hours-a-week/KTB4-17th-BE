package com.team.dating_backend.notification.enums;

import java.util.Arrays;
import java.util.List;

public enum NotificationCategory {
    ALL,
    INTEREST,
    CHAT,
    SYSTEM;

    public List<NotificationType> getTypes() {
        return Arrays.stream(NotificationType.values())
            .filter(type -> this == ALL || type.getCategory() == this)
            .toList();
    }
}
