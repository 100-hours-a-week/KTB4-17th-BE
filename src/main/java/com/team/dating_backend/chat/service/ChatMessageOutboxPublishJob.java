package com.team.dating_backend.chat.service;

import com.team.dating_backend.chat.dto.event.ChatMessageCreatedKafkaEvent;
import com.team.dating_backend.chat.entity.ChatMessageOutbox;
import com.team.dating_backend.chat.enums.ChatOutboxFailureCode;
import com.team.dating_backend.chat.exception.ChatMessageKafkaEventCreationException;
import com.team.dating_backend.chat.repository.ChatMessageOutboxRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.chat", name = "runtime-enabled", havingValue = "true")
public class ChatMessageOutboxPublishJob {

    private static final int BATCH_SIZE = 100;
    private static final int MAX_RETRY_BACKOFF_EXPONENT = 8;
    private static final int MAX_DATABASE_BACKOFF_EXPONENT = 6;
    private static final long MAX_DATABASE_BACKOFF_SECONDS = 60;
    private static final long LOCK_RENEW_INTERVAL_SECONDS = 3;

    private final ChatMessageOutboxRepository outboxRepository;
    private final ChatOutboxPublisherLockService lockService;
    private final ChatMessageKafkaPublisher kafkaPublisher;

    @Value("${app.chat.outbox.max-failures:10}")
    private int maxFailures = 10;

    private int consecutiveDatabaseFailures;
    private Instant nextDatabaseAttemptAt = Instant.MIN;

    public ChatMessageOutboxPublishJob(
        ChatMessageOutboxRepository outboxRepository,
        ChatOutboxPublisherLockService lockService,
        ChatMessageKafkaPublisher kafkaPublisher) {
        this.outboxRepository = outboxRepository;
        this.lockService = lockService;
        this.kafkaPublisher = kafkaPublisher;
    }

    @Scheduled(fixedDelayString = "${app.chat.outbox-publish-delay-ms:1000}")
    public void publishDueMessages() {
        if (Instant.now().isBefore(nextDatabaseAttemptAt)) {
            return;
        }

        try {
            if (!lockService.tryAcquire()) {
                return;
            }

            AtomicBoolean isLockOwned = new AtomicBoolean(true);
            try (ScheduledExecutorService lockRenewalExecutor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("chat-outbox-lock-renewal").factory())) {
                lockRenewalExecutor.scheduleAtFixedRate(
                    () -> renewLock(isLockOwned),
                    LOCK_RENEW_INTERVAL_SECONDS,
                    LOCK_RENEW_INTERVAL_SECONDS,
                    TimeUnit.SECONDS);
                List<ChatMessageOutbox> outboxes = outboxRepository
                    .findByPublishedAtIsNullAndFailedAtIsNullAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscIdAsc(
                        LocalDateTime.now(), PageRequest.of(0, BATCH_SIZE));

                for (ChatMessageOutbox outbox : outboxes) {
                    if (!isLockOwned.get() || !publish(outbox, isLockOwned)) {
                        return;
                    }
                }

                consecutiveDatabaseFailures = 0;
                nextDatabaseAttemptAt = Instant.MIN;
            }
        } catch (DataAccessException ignored) {
            scheduleDatabaseRetry();
        }
    }

    private void renewLock(AtomicBoolean isLockOwned) {
        if (!isLockOwned.get()) {
            return;
        }

        try {
            isLockOwned.compareAndSet(true, lockService.tryRenew());
        } catch (RuntimeException ignored) {
            isLockOwned.set(false);
        }
    }

    private boolean publish(ChatMessageOutbox outbox, AtomicBoolean isLockOwned) {
        try {
            CompletableFuture<SendResult<Long, ChatMessageCreatedKafkaEvent>> publishResult = kafkaPublisher
                .publish(outbox.getChatMessageId());
            while (true) {
                try {
                    publishResult.get(1, TimeUnit.SECONDS);
                    break;
                } catch (TimeoutException ignored) {
                    if (!isLockOwned.get()) {
                        return false;
                    }
                }
            }
            if (!isLockOwned.get()) {
                return false;
            }
        } catch (ChatMessageKafkaEventCreationException exception) {
            markFailed(outbox, exception.getFailureCode());
            return true;
        } catch (DataAccessException exception) {
            throw exception;
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | RuntimeException ignored) {
            retryOrFail(outbox, ChatOutboxFailureCode.BROKER_PUBLISH_FAILED);
            return true;
        }

        outbox.markPublished(LocalDateTime.now());
        outboxRepository.save(outbox);
        return true;
    }

    private void retryOrFail(
        ChatMessageOutbox outbox, ChatOutboxFailureCode failureCode) {
        LocalDateTime now = LocalDateTime.now();
        int nextFailureCount = outbox.getFailureCount() + 1;
        if (nextFailureCount >= maxFailures) {
            outbox.markFailed(now, failureCode.name());
            outboxRepository.save(outbox);
            log.error("채팅 메시지 Outbox 재시도 한도를 초과했습니다. outboxId={}, errorCode={}",
                outbox.getId(), ChatOutboxFailureCode.RETRY_LIMIT_EXCEEDED.name());
            return;
        }

        long maxDelaySeconds = 1L << Math.min(
            outbox.getFailureCount(), MAX_RETRY_BACKOFF_EXPONENT);
        long retryDelaySeconds = jitteredDelay(maxDelaySeconds);
        outbox.scheduleRetry(
            now.plusSeconds(retryDelaySeconds), failureCode.name());
        outboxRepository.save(outbox);
        log.warn("채팅 메시지 Outbox 발행을 재시도합니다. outboxId={}, failureCount={}, errorCode={}",
            outbox.getId(), nextFailureCount, failureCode.name());
    }

    private void markFailed(
        ChatMessageOutbox outbox, ChatOutboxFailureCode failureCode) {
        outbox.markFailed(LocalDateTime.now(), failureCode.name());
        outboxRepository.save(outbox);
        log.error("채팅 메시지 Outbox를 실패 상태로 격리했습니다. outboxId={}, errorCode={}",
            outbox.getId(), failureCode.name());
    }

    private void scheduleDatabaseRetry() {
        long maxDelaySeconds = Math.min(
            1L << Math.min(consecutiveDatabaseFailures, MAX_DATABASE_BACKOFF_EXPONENT),
            MAX_DATABASE_BACKOFF_SECONDS);
        long retryDelaySeconds = jitteredDelay(maxDelaySeconds);
        consecutiveDatabaseFailures++;
        nextDatabaseAttemptAt = Instant.now().plusSeconds(retryDelaySeconds);
        log.error("채팅 메시지 Outbox DB 접근에 실패했습니다. errorCode={}, retryDelaySeconds={}",
            ChatOutboxFailureCode.DATABASE_ACCESS_FAILED.name(), retryDelaySeconds);
    }

    private long jitteredDelay(long maxDelaySeconds) {
        long minimumDelaySeconds = Math.max(1, maxDelaySeconds / 2);
        long randomRange = maxDelaySeconds - minimumDelaySeconds + 1;
        return minimumDelaySeconds + ThreadLocalRandom.current().nextLong(randomRange);
    }
}
