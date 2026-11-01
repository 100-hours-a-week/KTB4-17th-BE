package com.team.dating_backend.chat.service;

import com.team.dating_backend.common.lock.repository.PublisherLockRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.chat", name = "runtime-enabled", havingValue = "true")
public class ChatOutboxPublisherLockService {

    private static final String LOCK_NAME = "chat-message-outbox-publisher";
    private static final long LOCK_TTL_MICROS = 10_000_000L;

    private final PublisherLockRepository lockRepository;
    private final String instanceId = UUID.randomUUID().toString();

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryAcquire() {
        return lockRepository.tryAcquire(
            LOCK_NAME, instanceId, LOCK_TTL_MICROS) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryRenew() {
        return lockRepository.tryRenew(
            LOCK_NAME, instanceId, LOCK_TTL_MICROS) == 1;
    }
}
