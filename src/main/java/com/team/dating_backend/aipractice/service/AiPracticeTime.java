package com.team.dating_backend.aipractice.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

public final class AiPracticeTime {

    public static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    private AiPracticeTime() {}

    public static LocalDate today() {
        return LocalDate.now(SERVICE_ZONE);
    }

    public static LocalDateTime now() {
        return LocalDateTime.now(SERVICE_ZONE);
    }
}
