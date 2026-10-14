package com.team.dating_backend.notification.enums;

public enum NotificationType {
    LIKE(NotificationCategory.INTEREST),
    MATCH(NotificationCategory.INTEREST),
    CHAT_MESSAGE(NotificationCategory.CHAT),
    PERSONA_TRAINING_COMPLETED(NotificationCategory.SYSTEM),
    FACE_ANALYSIS_COMPLETED(NotificationCategory.SYSTEM),
    AI_PRACTICE_CHAT_COMPLETED(NotificationCategory.SYSTEM),
    AI_SIMULATION_REPORT_COMPLETED(NotificationCategory.SYSTEM),
    NOTICE(NotificationCategory.SYSTEM);

    private final NotificationCategory category;

    NotificationType(NotificationCategory category) {
        this.category = category;
    }

    public NotificationCategory getCategory() {
        return category;
    }
}
