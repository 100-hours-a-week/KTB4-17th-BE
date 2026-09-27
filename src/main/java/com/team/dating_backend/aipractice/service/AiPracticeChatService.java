package com.team.dating_backend.aipractice.service;

import com.team.dating_backend.aipractice.config.AiPracticeProperties;
import com.team.dating_backend.aipractice.dto.request.AiPracticeChatCreateRequest;
import com.team.dating_backend.aipractice.dto.response.AiPracticeChatAcceptedResponse;
import com.team.dating_backend.aipractice.dto.response.AiPracticeUsageResponse;
import com.team.dating_backend.aipractice.entity.AiPracticeChat;
import com.team.dating_backend.aipractice.entity.AiPracticeOutbox;
import com.team.dating_backend.aipractice.entity.AiPracticeSession;
import com.team.dating_backend.aipractice.enums.AiPracticeChatStatus;
import com.team.dating_backend.aipractice.enums.AiPracticeErrorCode;
import com.team.dating_backend.aipractice.enums.AiPracticeOutboxCommandType;
import com.team.dating_backend.aipractice.enums.AiPracticeSessionStatus;
import com.team.dating_backend.aipractice.exception.AiPracticeBusinessException;
import com.team.dating_backend.aipractice.repository.AiPracticeChatRepository;
import com.team.dating_backend.aipractice.repository.AiPracticeOutboxRepository;
import com.team.dating_backend.aipractice.repository.AiPracticeSessionRepository;
import com.team.dating_backend.user.repository.UserRepository;
import java.time.LocalDate;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AiPracticeChatService {

    private static final Set<AiPracticeChatStatus> RESERVED_STATUSES = Set.of(
        AiPracticeChatStatus.GENERATING,
        AiPracticeChatStatus.COMPLETED);

    private final AiPracticeSessionRepository sessionRepository;
    private final AiPracticeChatRepository chatRepository;
    private final AiPracticeOutboxRepository outboxRepository;
    private final UserRepository userRepository;
    private final AiPracticeProperties properties;

    @Transactional
    public AiPracticeChatAcceptedResponse sendMessage(
        Long userId, Long sessionId, AiPracticeChatCreateRequest request) {
        lockUser(userId);
        AiPracticeSession session = lockOwnedSession(sessionId, userId);
        AiPracticeChat existing = chatRepository.findBySession_IdAndClientMessageId(
            sessionId, request.clientMessageId()).orElse(null);
        if (existing != null) {
            if (!existing.getUserMessage().equals(request.userMessage())) {
                throw new AiPracticeBusinessException(
                    AiPracticeErrorCode.CLIENT_MESSAGE_ID_CONFLICT);
            }
            return toAcceptedResponse(existing);
        }

        requireActiveSession(session);
        if (session.getAiSessionId() == null && session.getTargetMemberId() == null) {
            throw new AiPracticeBusinessException(AiPracticeErrorCode.TARGET_MEMBER_UNAVAILABLE);
        }
        if (chatRepository.existsBySession_IdAndStatus(
            sessionId, AiPracticeChatStatus.GENERATING)) {
            throw new AiPracticeBusinessException(AiPracticeErrorCode.GENERATION_IN_PROGRESS);
        }

        LocalDate usageDate = AiPracticeTime.today();
        requireDailyCapacity(userId, usageDate);
        AiPracticeChat chat = chatRepository.save(new AiPracticeChat(
            session,
            request.clientMessageId(),
            request.userMessage(),
            usageDate,
            AiPracticeTime.now()));
        outboxRepository.save(new AiPracticeOutbox(
            AiPracticeOutboxCommandType.GENERATE,
            sessionId,
            chat.getId(),
            chat.getGenerationAttempt(),
            AiPracticeTime.now()));
        return toAcceptedResponse(chat);
    }

    @Transactional
    public AiPracticeChatAcceptedResponse retry(
        Long userId, Long sessionId, Long chatId) {
        lockUser(userId);
        AiPracticeSession session = lockOwnedSession(sessionId, userId);
        requireActiveSession(session);
        AiPracticeChat chat = chatRepository.findByIdAndSession_Id(chatId, sessionId)
            .orElseThrow(() -> new AiPracticeBusinessException(AiPracticeErrorCode.CHAT_NOT_FOUND));
        if (chat.getStatus() != AiPracticeChatStatus.FAILED) {
            throw new AiPracticeBusinessException(AiPracticeErrorCode.CHAT_NOT_RETRYABLE);
        }
        if (chatRepository.existsBySession_IdAndStatus(
            sessionId, AiPracticeChatStatus.GENERATING)) {
            throw new AiPracticeBusinessException(AiPracticeErrorCode.GENERATION_IN_PROGRESS);
        }

        LocalDate usageDate = AiPracticeTime.today();
        requireDailyCapacity(userId, usageDate);
        chat.retry(usageDate);
        outboxRepository.save(new AiPracticeOutbox(
            AiPracticeOutboxCommandType.GENERATE,
            sessionId,
            chat.getId(),
            chat.getGenerationAttempt(),
            AiPracticeTime.now()));
        return toAcceptedResponse(chat);
    }

    @Transactional(readOnly = true)
    public AiPracticeUsageResponse getTodayUsage(Long userId) {
        LocalDate today = AiPracticeTime.today();
        long used = chatRepository.countUsageByUserIdAndUsageDateAndStatus(
            userId, today, AiPracticeChatStatus.COMPLETED);
        long reserved = chatRepository.countUsageByUserIdAndUsageDateAndStatus(
            userId, today, AiPracticeChatStatus.GENERATING);
        int dailyLimit = properties.getDailyLimit();
        int remaining = (int) Math.max(0, dailyLimit - used - reserved);
        return new AiPracticeUsageResponse(today, used, reserved, dailyLimit, remaining);
    }

    private void lockUser(Long userId) {
        userRepository.findByIdForUpdate(userId)
            .orElseThrow(() -> new AiPracticeBusinessException(AiPracticeErrorCode.USER_NOT_FOUND));
    }

    private AiPracticeSession lockOwnedSession(Long sessionId, Long userId) {
        return sessionRepository.findByIdAndUserIdForUpdate(sessionId, userId)
            .orElseThrow(() -> new AiPracticeBusinessException(AiPracticeErrorCode.SESSION_NOT_FOUND));
    }

    private void requireActiveSession(AiPracticeSession session) {
        if (session.getStatus() != AiPracticeSessionStatus.ACTIVE) {
            throw new AiPracticeBusinessException(AiPracticeErrorCode.SESSION_ENDED);
        }
    }

    private void requireDailyCapacity(Long userId, LocalDate usageDate) {
        long reservedAndCompleted = chatRepository.countReservedAndCompletedUsage(
            userId, usageDate, RESERVED_STATUSES);
        if (reservedAndCompleted >= properties.getDailyLimit()) {
            throw new AiPracticeBusinessException(AiPracticeErrorCode.DAILY_LIMIT_EXCEEDED);
        }
    }

    private AiPracticeChatAcceptedResponse toAcceptedResponse(AiPracticeChat chat) {
        return new AiPracticeChatAcceptedResponse(
            chat.getSession().getId(),
            chat.getId(),
            chat.getStatus(),
            chat.getCreatedAt());
    }
}
