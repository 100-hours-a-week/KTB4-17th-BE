package com.team.dating_backend.chat.service;

import com.team.dating_backend.chat.dto.event.ChatMessageCreatedEvent;
import com.team.dating_backend.chat.entity.ChatMessage;
import com.team.dating_backend.chat.entity.ChatMessageOutbox;
import com.team.dating_backend.chat.entity.ChatParticipant;
import com.team.dating_backend.chat.enums.ChatOutboxFailureCode;
import com.team.dating_backend.chat.enums.ChatParticipantStatus;
import com.team.dating_backend.chat.repository.ChatMessageOutboxRepository;
import com.team.dating_backend.chat.repository.ChatMessageRepository;
import com.team.dating_backend.chat.repository.ChatParticipantRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ChatMessageOutboxPublishJob {

    private static final int BATCH_SIZE = 100;
    private static final String MESSAGE_DESTINATION = "/queue/chat-messages";
    private static final int MAX_RETRY_BACKOFF_EXPONENT = 8;
    private static final int MAX_DATABASE_BACKOFF_EXPONENT = 6;
    private static final long MAX_DATABASE_BACKOFF_SECONDS = 60;

    private final ChatMessageOutboxRepository outboxRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatParticipantRepository chatParticipantRepository;
    private final SimpMessagingTemplate messagingTemplate;

    @Value("${app.chat.outbox.max-failures:10}")
    private int maxFailures = 10;

    private int consecutiveDatabaseFailures;
    private Instant nextDatabaseAttemptAt = Instant.MIN;

    public ChatMessageOutboxPublishJob(
        ChatMessageOutboxRepository outboxRepository,
        ChatMessageRepository chatMessageRepository,
        ChatParticipantRepository chatParticipantRepository,
        SimpMessagingTemplate messagingTemplate) {
        this.outboxRepository = outboxRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.chatParticipantRepository = chatParticipantRepository;
        this.messagingTemplate = messagingTemplate;
    }

    @Scheduled(fixedDelayString = "${app.chat.outbox-publish-delay-ms:1000}")
    public void publishDueMessages() {
        if (Instant.now().isBefore(nextDatabaseAttemptAt)) {
            return;
        }

        try {
            List<ChatMessageOutbox> outboxes = outboxRepository
                .findByPublishedAtIsNullAndFailedAtIsNullAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscIdAsc(
                    LocalDateTime.now(), PageRequest.of(0, BATCH_SIZE));

            for (ChatMessageOutbox outbox : outboxes) {
                publish(outbox);
            }

            consecutiveDatabaseFailures = 0;
            nextDatabaseAttemptAt = Instant.MIN;
        } catch (DataAccessException ignored) {
            scheduleDatabaseRetry();
        }
    }

    private void publish(ChatMessageOutbox outbox) {
        ChatMessage message = chatMessageRepository.findById(outbox.getChatMessageId())
            .orElse(null);
        if (message == null) {
            markFailed(outbox, ChatOutboxFailureCode.MESSAGE_NOT_FOUND);
            return;
        }

        List<ChatParticipant> participants = chatParticipantRepository
            .findAllByChatRoomId(message.getChatRoomId());
        boolean senderIsParticipant = participants.stream()
            .anyMatch(participant -> participant.getId().equals(message.getSenderParticipantId()));
        if (participants.size() != 2 || !senderIsParticipant) {
            markFailed(outbox, ChatOutboxFailureCode.PARTICIPANTS_INVALID);
            return;
        }

        for (ChatParticipant participant : participants) {
            if (participant.getStatus() != ChatParticipantStatus.ACTIVE) {
                continue;
            }

            ChatMessageCreatedEvent event = new ChatMessageCreatedEvent(
                message.getChatRoomId(),
                message.getId(),
                message.getClientMessageId(),
                participant.getId().equals(message.getSenderParticipantId()),
                message.getMessageType(),
                message.getTextContent(),
                message.getCreatedAt());

            try {
                messagingTemplate.convertAndSendToUser(
                    participant.getUserId().toString(), MESSAGE_DESTINATION, event);
            } catch (RuntimeException ignored) {
                retryOrFail(outbox, ChatOutboxFailureCode.BROKER_PUBLISH_FAILED);
                return;
            }
        }

        outbox.markPublished(LocalDateTime.now());
        outboxRepository.save(outbox);
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
