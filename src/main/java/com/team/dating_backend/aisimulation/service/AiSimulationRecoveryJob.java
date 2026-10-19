package com.team.dating_backend.aisimulation.service;

import com.team.dating_backend.aisimulation.config.AiSimulationProperties;
import com.team.dating_backend.aisimulation.enums.AiSimulationSessionStatus;
import com.team.dating_backend.aisimulation.repository.AiSimulationSessionRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
// 같은 AI 작업을 여러 서버가 복구하지 않도록 실행 서버를 선택한다.
@ConditionalOnProperty(
    prefix = "app.workers", name = "ai-simulation-recovery-enabled",
    havingValue = "true", matchIfMissing = true
)
@RequiredArgsConstructor
@Slf4j
public class AiSimulationRecoveryJob implements ApplicationRunner {
    private final AiSimulationSessionRepository sessionRepository;
    private final AiSimulationPersistenceService persistenceService;
    private final AiSimulationProperties properties;

    @Override
    public void run(ApplicationArguments args) {
        recoverExpired();
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void recoverExpired() {
        LocalDateTime deadline = LocalDateTime.now(ZoneOffset.UTC)
            .minusSeconds(properties.getMaxProcessingAgeSeconds());
        List<Long> expiredIds;
        try {
            expiredIds = sessionRepository.findExpiredIds(AiSimulationSessionStatus.IN_PROGRESS, deadline,
                PageRequest.of(0, 100));
        } catch (RuntimeException exception) {
            log.error("AI simulation recovery scan failed. exceptionType={}", exception.getClass().getSimpleName());
            return;
        }
        for (Long sessionId : expiredIds) {
            try {
                persistenceService.failIfExpired(sessionId, deadline);
            } catch (RuntimeException exception) {
                log.error("AI simulation recovery failed. simulationId={}, exceptionType={}", sessionId,
                    exception.getClass().getSimpleName());
            }
        }
    }
}
