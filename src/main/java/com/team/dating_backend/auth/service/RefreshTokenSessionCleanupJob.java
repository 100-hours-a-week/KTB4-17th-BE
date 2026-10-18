package com.team.dating_backend.auth.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RefreshTokenSessionCleanupJob {

    private final AuthTokenService authTokenService;

    @Scheduled(fixedDelayString = "${app.auth.refresh-session-cleanup-delay-ms:86400000}")
    public void deleteExpiredSessions() {
        authTokenService.deleteExpiredSessions();
    }
}
