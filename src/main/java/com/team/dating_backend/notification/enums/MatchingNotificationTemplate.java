package com.team.dating_backend.notification.enums;

import org.springframework.util.StringUtils;

public enum MatchingNotificationTemplate {
    LIKE(
        "새로운 좋아요",
        "%s님이 당신에게 마음을 전했어요.",
        "당신에게 마음이 도착했어요.",
        "PROFILE"),
    MATCH(
        "매칭 성사",
        "서로의 마음이 연결되었어요. 채팅을 시작해 보세요.",
        "CHAT_ROOM");

    private final String title;
    private final String bodyTemplate;
    private final String defaultBody;
    private final String targetType;

    MatchingNotificationTemplate(String title, String body, String targetType) {
        this(title, body, body, targetType);
    }

    MatchingNotificationTemplate(
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
