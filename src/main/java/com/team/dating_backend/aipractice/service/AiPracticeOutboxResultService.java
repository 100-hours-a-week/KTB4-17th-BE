package com.team.dating_backend.aipractice.service;

import com.team.dating_backend.aipractice.dto.event.AiPracticeChatUpdatedEvent;
import com.team.dating_backend.aipractice.entity.AiPracticeChat;
import com.team.dating_backend.aipractice.entity.AiPracticeOutbox;
import com.team.dating_backend.aipractice.entity.AiPracticeSession;
import com.team.dating_backend.aipractice.enums.AiPracticeChatStatus;
import com.team.dating_backend.aipractice.enums.AiPracticeErrorCode;
import com.team.dating_backend.aipractice.enums.AiPracticeOutboxCommandType;
import com.team.dating_backend.aipractice.exception.AiPracticeBusinessException;
import com.team.dating_backend.aipractice.repository.AiPracticeChatRepository;
import com.team.dating_backend.aipractice.repository.AiPracticeOutboxRepository;
import com.team.dating_backend.aipractice.repository.AiPracticeSessionRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AiPracticeOutboxResultService {

    private final AiPracticeOutboxRepository outboxRepository;
    private final AiPracticeSessionRepository sessionRepository;
    private final AiPracticeChatRepository chatRepository;
    private final AiPracticeEndCommandQueue endCommandQueue;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public boolean attachAiSessionId(Long outboxId, String token, String aiSessionId) {
        AiPracticeOutbox outbox = findClaimedOutbox(outboxId, token);
        if (outbox == null) {
            return false;
        }
        AiPracticeSession session = sessionRepository.findByIdForUpdate(outbox.getSessionId())
            .orElseThrow(() -> new AiPracticeBusinessException(AiPracticeErrorCode.SESSION_NOT_FOUND));
        attachAiSessionId(session, aiSessionId);
        return true;
    }

    @Transactional
    public boolean markGenerationCompleted(
        Long outboxId, String token, String aiSessionId, String aiResponse) {
        AiPracticeOutbox outbox = findClaimedOutbox(outboxId, token);
        if (outbox == null) {
            return false;
        }
        AiPracticeSession session = sessionRepository.findByIdForUpdate(outbox.getSessionId())
            .orElseThrow(() -> new AiPracticeBusinessException(AiPracticeErrorCode.SESSION_NOT_FOUND));
        attachAiSessionId(session, aiSessionId);

        AiPracticeChat chat = outbox.getChatId() == null
            ? null
            : chatRepository.findByIdAndSessionId(outbox.getChatId(), outbox.getSessionId())
                .orElse(null);
        if (chat != null
            && chat.getGenerationAttempt() == outbox.getGenerationAttempt()
            && chat.getStatus() == AiPracticeChatStatus.GENERATING) {
            chat.complete(aiResponse, AiPracticeTime.now());
            eventPublisher.publishEvent(new AiPracticeChatUpdatedEvent(
                session.getUserId(),
                session.getId(),
                chat.getId(),
                chat.getStatus(),
                chat.getAiResponse(),
                null,
                chat.getCompletedAt()));
        }

        outbox.markPublished(AiPracticeTime.now());
        endCommandQueue.enqueueIfReady(session);
        return true;
    }

    @Transactional
    public boolean markGenerationFailed(Long outboxId, String token, String failureCode) {
        AiPracticeOutbox outbox = findClaimedOutbox(outboxId, token);
        if (outbox == null) {
            return false;
        }
        AiPracticeSession session = sessionRepository.findByIdForUpdate(outbox.getSessionId())
            .orElse(null);
        outbox.markFailed(AiPracticeTime.now(), failureCode);
        if (session == null) {
            return true;
        }

        AiPracticeChat chat = outbox.getChatId() == null
            ? null
            : chatRepository.findByIdAndSessionId(outbox.getChatId(), outbox.getSessionId())
                .orElse(null);
        if (chat != null
            && chat.getGenerationAttempt() == outbox.getGenerationAttempt()
            && chat.getStatus() == AiPracticeChatStatus.GENERATING) {
            chat.fail(failureCode);
            eventPublisher.publishEvent(new AiPracticeChatUpdatedEvent(
                session.getUserId(),
                session.getId(),
                chat.getId(),
                chat.getStatus(),
                null,
                chat.getFailureCode(),
                null));
        }
        endCommandQueue.enqueueIfReady(session);
        return true;
    }

    @Transactional
    public boolean markEndSubmitted(Long outboxId, String token) {
        AiPracticeOutbox outbox = findClaimedOutbox(outboxId, token);
        if (outbox == null) {
            return false;
        }
        outbox.markPublished(AiPracticeTime.now());
        return true;
    }

    @Transactional
    public boolean markSkipped(Long outboxId, String token) {
        AiPracticeOutbox outbox = findClaimedOutbox(outboxId, token);
        if (outbox == null) {
            return false;
        }
        outbox.markPublished(AiPracticeTime.now());
        return true;
    }

    @Transactional
    public boolean recordFailure(Long outboxId, String token, String failureType, int maxFailures,
        LocalDateTime nextAttemptAt) {
        AiPracticeOutbox outbox = findClaimedOutbox(outboxId, token);
        if (outbox == null) {
            return false;
        }
        int nextFailureCount = outbox.getFailureCount() + 1;
        if (nextFailureCount < maxFailures) {
            outbox.scheduleRetry(nextAttemptAt, failureType);
            return true;
        }

        outbox.markFailed(AiPracticeTime.now(), failureType);
        if (outbox.getCommandType() != AiPracticeOutboxCommandType.GENERATE) {
            return true;
        }

        AiPracticeSession session = sessionRepository.findByIdForUpdate(outbox.getSessionId())
            .orElse(null);
        AiPracticeChat chat = outbox.getChatId() == null
            ? null
            : chatRepository.findByIdAndSessionId(outbox.getChatId(), outbox.getSessionId())
                .orElse(null);
        if (chat != null
            && chat.getGenerationAttempt() == outbox.getGenerationAttempt()
            && chat.getStatus() == AiPracticeChatStatus.GENERATING) {
            chat.fail("AI_SERVER_UNAVAILABLE");
            if (session != null) {
                eventPublisher.publishEvent(new AiPracticeChatUpdatedEvent(
                    session.getUserId(),
                    session.getId(),
                    chat.getId(),
                    chat.getStatus(),
                    null,
                    chat.getFailureCode(),
                    null));
                endCommandQueue.enqueueIfReady(session);
            }
        }
        return true;
    }

    private AiPracticeOutbox findClaimedOutbox(Long outboxId, String token) {
        return outboxRepository.findClaimedByIdForUpdate(outboxId, token).orElse(null);
    }

    private void attachAiSessionId(AiPracticeSession session, String aiSessionId) {
        if (aiSessionId == null || aiSessionId.isBlank()) {
            throw new AiPracticeBusinessException(AiPracticeErrorCode.AI_SERVER_RESPONSE_INVALID);
        }
        try {
            session.attachAiSessionId(aiSessionId);
        } catch (IllegalStateException exception) {
            throw new AiPracticeBusinessException(
                AiPracticeErrorCode.AI_SESSION_ID_CONFLICT, exception);
        }
    }
}
