package com.team.dating_backend.aipractice.service;

import com.team.dating_backend.aipractice.config.AiPracticeProperties;
import com.team.dating_backend.aipractice.entity.AiPracticeOutbox;
import com.team.dating_backend.aipractice.repository.AiPracticeOutboxRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AiPracticeOutboxClaimService {

    private static final long MICROS_PER_MILLI = 1_000L;

    private final AiPracticeOutboxRepository outboxRepository;
    private final AiPracticeProperties properties;

    @Transactional
    public Optional<AiPracticeOutbox> tryClaim(Long outboxId) {
        if (outboxId == null || outboxId <= 0) {
            return Optional.empty();
        }

        String token = UUID.randomUUID().toString();
        if (outboxRepository.claim(
            outboxId, token, AiPracticeTime.now(), claimDurationMicros()) != 1) {
            return Optional.empty();
        }
        return outboxRepository.findById(outboxId);
    }

    @Transactional
    public boolean renew(Long outboxId, String token) {
        if (outboxId == null || outboxId <= 0 || token == null || token.isBlank()) {
            return false;
        }
        return outboxRepository.renew(outboxId, token, claimDurationMicros()) == 1;
    }

    private long claimDurationMicros() {
        return Math.multiplyExact(properties.getOutboxClaimDurationMs(), MICROS_PER_MILLI);
    }
}
