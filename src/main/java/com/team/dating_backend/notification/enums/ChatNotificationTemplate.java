package com.team.dating_backend.notification.enums;

import org.springframework.util.StringUtils;

public enum ChatNotificationTemplate {
    CHAT_MESSAGE(
        "새로운 메시지",
        "%s님에게서 메시지가 도착했어요.",
        "새로운 메시지가 도착했어요.",
        "CHAT_ROOM");

    private final String title;
    private final String bodyTemplate;
    private final String defaultBody;
    private final String targetType;

    ChatNotificationTemplate(
        String title, String bodyTemplate, String defaultBody, String targetType) {
        this.title = title;
        this.bodyTemplate = bodyTemplate;
        this.defaultBody = defaultBody;
        this.targetType = targetType;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return defaultBody;
    }

    public String formatBody(String nickname) {
        return StringUtils.hasText(nickname) ? bodyTemplate.formatted(nickname) : defaultBody;
    }

    public String getTargetType() {
        return targetType;
    }
}
