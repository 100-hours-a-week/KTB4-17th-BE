package com.team.dating_backend.notification.enums;

public enum AiNotificationTemplate {
    SIMULATION_COMPLETED(
        "시뮬레이션 완료",
        "두 사람의 대화 시뮬레이션과 분석 보고서가 준비됐어요.",
        "AI_SIMULATION");

    private final String title;
    private final String body;
    private final String targetType;

    AiNotificationTemplate(String title, String body, String targetType) {
        this.title = title;
        this.body = body;
        this.targetType = targetType;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public String getTargetType() {
        return targetType;
    }
}
