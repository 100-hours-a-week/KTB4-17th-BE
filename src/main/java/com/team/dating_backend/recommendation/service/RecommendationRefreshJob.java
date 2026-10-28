package com.team.dating_backend.recommendation.service;

import com.team.dating_backend.common.lock.repository.PublisherLockRepository;
import com.team.dating_backend.user.enums.UserStatus;
import com.team.dating_backend.user.repository.UserRepository;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.recommendation.refresh", name = "enabled", havingValue = "true")
public class RecommendationRefreshJob {

    private static final String LOCK_NAME = "recommendation-refresh";
    private static final long LEASE_MICROS = 300_000_000L;

    private final UserRepository userRepository;
    private final RecommendationBatchCreateService refreshService;
    private final RecommendationHistoryCleanupService cleanupService;
    private final PublisherLockRepository lockRepository;
    private final int pageSize;
    private final int cleanupChunks;

    public RecommendationRefreshJob(UserRepository userRepository,
        RecommendationBatchCreateService refreshService,
        RecommendationHistoryCleanupService cleanupService,
        PublisherLockRepository lockRepository,
        @Value("${app.recommendation.refresh.page-size:100}") int pageSize,
        @Value("${app.recommendation.refresh.cleanup-chunks:100}") int cleanupChunks) {
        if (pageSize <= 0 || cleanupChunks < 0) {
            throw new IllegalArgumentException("Invalid recommendation refresh limits");
        }
        this.userRepository = userRepository;
        this.refreshService = refreshService;
        this.cleanupService = cleanupService;
        this.lockRepository = lockRepository;
        this.pageSize = pageSize;
        this.cleanupChunks = cleanupChunks;
    }

    @Scheduled(
        cron = "${app.recommendation.refresh.cron:-}",
        zone = "${app.recommendation.refresh.zone:Asia/Seoul}"
    )
    public void refresh() {
        String executionId = UUID.randomUUID().toString();
        if (!acquire(executionId)) {
            return;
        }
        int succeeded = 0;
        int failed = 0;
        try {
            long throughId = userRepository.findMaxUserId();
            long afterId = 0;
            while (afterId < throughId) {
                List<Long> ids = userRepository.findIdsForRefresh(UserStatus.ACTIVE,
                    afterId, throughId, PageRequest.of(0, pageSize));
                if (ids.isEmpty()) {
                    break;
                }
                for (Long userId : ids) {
                    if (!acquire(executionId)) {
                        log.warn("Recommendation refresh lease lost");
                        return;
                    }
                    try {
                        refreshService.refreshRecommendationBatch(userId);
                        succeeded++;
                    } catch (RuntimeException exception) {
                        failed++;
                        log.warn("Recommendation refresh failed: userId={}", userId, exception);
                    }
                    afterId = userId;
                }
            }
            for (int chunk = 0; chunk < cleanupChunks; chunk++) {
                if (!acquire(executionId) || cleanupService.purgeChunk(500) == 0) {
                    break;
                }
            }
        } finally {
            lockRepository.release(LOCK_NAME, executionId);
            log.info("Recommendation refresh finished: succeeded={}, failed={}", succeeded, failed);
        }
    }

    private boolean acquire(String executionId) {
        return lockRepository.tryAcquireOrExtend(LOCK_NAME, executionId, LEASE_MICROS) == 1;
    }
}
