package com.team.dating_backend.chat.service;

import com.team.dating_backend.chat.dto.event.ChatMessageCreatedEvent;
import com.team.dating_backend.chat.monitoring.ChatOutboxMetrics;
import com.team.dating_backend.chat.entity.ChatMessage;
import com.team.dating_backend.chat.entity.ChatParticipant;
import com.team.dating_backend.chat.enums.ChatOutboxFailureCode;
import com.team.dating_backend.chat.enums.ChatParticipantStatus;
import com.team.dating_backend.chat.repository.ChatMessageRepository;
import com.team.dating_backend.chat.repository.ChatParticipantRepository;
import com.team.dating_backend.chat.service.ChatMessageOutboxClaimService.ClaimedBatch;
import com.team.dating_backend.chat.service.ChatMessageOutboxClaimService.ClaimedOutbox;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

@Slf4j
@Component
@ConditionalOnProperty(
    prefix = "app.workers", name = "chat-outbox-enabled",
    havingValue = "true", matchIfMissing = true
)
public class ChatMessageOutboxPublishJob {

    private static final int BATCH_SIZE = 100;
    private static final String MESSAGE_DESTINATION = "/queue/chat-messages";
    private static final int MAX_RETRY_BACKOFF_EXPONENT = 8;
    private static final int MAX_DATABASE_BACKOFF_EXPONENT = 6;
    private static final long MAX_DATABASE_BACKOFF_SECONDS = 60;

    private final ChatMessageOutboxClaimService claims;
    private final ChatOutboxMetrics metrics;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatParticipantRepository chatParticipantRepository;
    private final RabbitUserEventPublisher eventPublisher;
    private final ChatOutboxStartGate startGate;

    @Value("${app.chat.outbox.max-failures:10}")
    private int maxFailures = 10;

    private int consecutiveDatabaseFailures;
    private Instant nextDatabaseAttemptAt = Instant.MIN;
    private LongSupplier nanoTime = System::nanoTime;

    public ChatMessageOutboxPublishJob(
        ChatMessageOutboxClaimService claims,
        ChatMessageRepository chatMessageRepository,
        ChatParticipantRepository chatParticipantRepository,
        RabbitUserEventPublisher eventPublisher, ChatOutboxMetrics metrics, ChatOutboxStartGate startGate) {
        this.claims = claims;
        this.metrics = metrics;
        this.chatMessageRepository = chatMessageRepository;
        this.chatParticipantRepository = chatParticipantRepository;
        this.eventPublisher = eventPublisher;
        this.startGate = startGate;
    }

    @Scheduled(fixedDelayString = "${app.chat.outbox-publish-delay-ms:1000}")
    public void publishDueMessages() {
        // 전환 전 동기화가 끝나기 전에 과거 메시지가 발행되는 것을 막는다.
        if (!startGate.isOpen()) {
            return;
        }
        if (Instant.now().isBefore(nextDatabaseAttemptAt)) {
            return;
        }

        try {
            // DB 호출 전에 시간을 재서 느린 선점 조회가 유효기간을 늘리지 않게 한다.
            long claimStartedAt = nanoTime.getAsLong();
            ClaimedBatch batch = claims.claimBatch(BATCH_SIZE);
            List<ClaimedOutbox> outboxes = batch.items();
            BatchLease lease = new BatchLease(batch, claimStartedAt);

            for (ClaimedOutbox outbox : outboxes) {
                try {
                    lease.ensureValid();
                    publish(outbox, batch.token(), lease);
                    lease.finished(outbox.id());
                } catch (RuntimeException exception) {
                    log.error("Outbox processing failed: outboxId={}, messageId={}, errorType={}",
                        outbox.id(), outbox.chatMessageId(), exception.getClass().getSimpleName());
                    throw exception;
                }
            }

            metrics.pollSucceeded();
            consecutiveDatabaseFailures = 0;
            nextDatabaseAttemptAt = Instant.MIN;
        } catch (DataAccessException | TransactionException exception) {
            scheduleDatabaseRetry(exception);
        } catch (ClaimLostException exception) {
            // 미완료 행은 현재 작업자가 처리하거나 선점 만료 후 복구한다.
            log.warn("Outbox claim lost; stop processing the current batch");
        }
    }

    private void publish(ClaimedOutbox outbox, String token, BatchLease lease) {
        ChatMessage message = chatMessageRepository.findByIdWithImageFile(outbox.chatMessageId())
            .orElse(null);
        if (message == null) {
            markFailed(outbox, token, ChatOutboxFailureCode.MESSAGE_NOT_FOUND);
            return;
        }

        List<ChatParticipant> participants = chatParticipantRepository
            .findAllByChatRoomId(message.getChatRoomId());
        boolean senderIsParticipant = participants.stream()
            .anyMatch(participant -> participant.getId().equals(message.getSenderParticipantId()));
        if (participants.size() != 2 || !senderIsParticipant) {
            markFailed(outbox, token, ChatOutboxFailureCode.PARTICIPANTS_INVALID);
            return;
        }

        ChatParticipant otherParticipant = participants.stream()
            .filter(participant -> !participant.getId().equals(message.getSenderParticipantId()))
            .findFirst()
            .orElse(null);
        if (otherParticipant == null) {
            markFailed(outbox, token, ChatOutboxFailureCode.PARTICIPANTS_INVALID);
            return;
        }

        for (ChatParticipant participant : participants) {
            if (participant.getStatus() != ChatParticipantStatus.ACTIVE) {
                continue;
            }

            boolean mine = participant.getId().equals(message.getSenderParticipantId());
            boolean unreadByOther = otherParticipant.getLastReadMessageId() == null
                || message.getId() > otherParticipant.getLastReadMessageId();
            ChatMessageCreatedEvent event = new ChatMessageCreatedEvent(
                message.getChatRoomId(),
                message.getId(),
                message.getClientMessageId(),
                mine,
                message.getMessageType(),
                message.getTextContent(),
                message.getImageFileId(),
                mine && unreadByOther ? 1 : 0,
                message.getCreatedAt());

            // 선점 상실을 RabbitMQ 발행 실패로 처리하지 않는다.
            lease.ensureValid();
            try {
                eventPublisher.publish(
                    participant.getUserId().toString(), MESSAGE_DESTINATION, event);
            } catch (RuntimeException exception) {
                log.warn(
                    "RabbitMQ publish failed: outboxId={}, messageId={}, recipientUserId={}, attempt={}, errorType={}",
                    outbox.id(), message.getId(), participant.getUserId(),
                    outbox.rabbitFailureCount() + 1, exception.getClass().getSimpleName());
                retryOrFail(outbox, token, ChatOutboxFailureCode.BROKER_PUBLISH_FAILED);
                return;
            }
        }

        // 발행 성공 후 DB 저장이 실패할 수 있으므로 브로커 예외 처리와 분리한다.
        requireOwned(claims.complete(outbox.id(), token));
        metrics.completed(outbox.createdAt(), LocalDateTime.now());
    }

    private void retryOrFail(
        ClaimedOutbox outbox, String token, ChatOutboxFailureCode failureCode) {
        int nextFailureCount = outbox.rabbitFailureCount() + 1;
        if (nextFailureCount >= maxFailures) {
            requireOwned(claims.fail(outbox.id(), token, failureCode.name()));
            metrics.failed();
            log.error("Outbox retry limit reached: outboxId={}, messageId={}, failureCount={}, failureCode={}",
                outbox.id(), outbox.chatMessageId(), nextFailureCount, failureCode);
            return;
        }

        long maxDelaySeconds = 1L << Math.min(
            outbox.rabbitFailureCount(), MAX_RETRY_BACKOFF_EXPONENT);
        long retryDelaySeconds = jitteredDelay(maxDelaySeconds);
        requireOwned(claims.retry(outbox.id(), token, retryDelaySeconds, failureCode.name()));
        metrics.retried();
        log.warn("Outbox retry scheduled: outboxId={}, messageId={}, failureCount={}, failureCode={}, delaySeconds={}",
            outbox.id(), outbox.chatMessageId(), nextFailureCount, failureCode, retryDelaySeconds);
    }

    private void markFailed(
        ClaimedOutbox outbox, String token, ChatOutboxFailureCode failureCode) {
        requireOwned(claims.fail(outbox.id(), token, failureCode.name()));
        metrics.failed();
        log.error("Outbox failed: outboxId={}, messageId={}, failureCode={}",
            outbox.id(), outbox.chatMessageId(), failureCode);
    }

    private void requireOwned(boolean updated) {
        if (!updated) {
            throw new ClaimLostException("Claim token no longer owns the outbox row");
        }
    }

    /** 메시지마다 조회하지 않고 미완료 행의 선점만 주기적으로 갱신한다. */
    private final class BatchLease {

        private final String token;
        private final Set<Long> pending = new LinkedHashSet<>();
        private long lastRenewalStartedAt;

        private BatchLease(ClaimedBatch batch, long claimStartedAt) {
            token = batch.token();
            batch.items().forEach(outbox -> pending.add(outbox.id()));
            lastRenewalStartedAt = claimStartedAt;
        }

        private void ensureValid() {
            long now = nanoTime.getAsLong();
            long elapsed = now - lastRenewalStartedAt;
            if (elapsed >= claims.leaseNanos()) {
                throw new ClaimLostException("Local lease deadline passed; stop publishing this batch");
            }
            if (elapsed >= claims.renewalNanos()) {
                int renewed = claims.renew(token, List.copyOf(pending));
                // 갱신이 지연돼도 이전 만료 시각을 넘긴 선점은 되살리지 않는다.
                if (renewed != pending.size()
                    || nanoTime.getAsLong() - lastRenewalStartedAt >= claims.leaseNanos()) {
                    throw new ClaimLostException("Could not renew all remaining outbox claims");
                }
                lastRenewalStartedAt = now;
            }
        }

        private void finished(long id) {
            pending.remove(id);
        }
    }

    private static final class ClaimLostException extends RuntimeException {

        private ClaimLostException(String message) {
            super(message);
        }
    }

    private void scheduleDatabaseRetry(RuntimeException exception) {
        metrics.databaseError();
        long maxDelaySeconds = Math.min(
            1L << Math.min(consecutiveDatabaseFailures, MAX_DATABASE_BACKOFF_EXPONENT),
            MAX_DATABASE_BACKOFF_SECONDS);
        long retryDelaySeconds = jitteredDelay(maxDelaySeconds);
        consecutiveDatabaseFailures++;
        nextDatabaseAttemptAt = Instant.now().plusSeconds(retryDelaySeconds);
        log.error(
            "Outbox database operation failed: failureCount={}, retryDelaySeconds={}, nextAttemptAt={}, errorType={}",
            consecutiveDatabaseFailures, retryDelaySeconds, nextDatabaseAttemptAt,
            exception.getClass().getSimpleName());
    }

    private long jitteredDelay(long maxDelaySeconds) {
        long minimumDelaySeconds = Math.max(1, maxDelaySeconds / 2);
        long randomRange = maxDelaySeconds - minimumDelaySeconds + 1;
        return minimumDelaySeconds + ThreadLocalRandom.current().nextLong(randomRange);
    }
}
