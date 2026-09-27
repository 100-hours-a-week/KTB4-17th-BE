package com.team.dating_backend.aipractice.service;

import com.team.dating_backend.aipractice.dto.event.AiPracticeChatUpdatedEvent;
import com.team.dating_backend.aipractice.dto.request.AiPracticeGenerationCallbackRequest;
import com.team.dating_backend.aipractice.entity.AiPracticeChat;
import com.team.dating_backend.aipractice.entity.AiPracticeSession;
import com.team.dating_backend.aipractice.enums.AiPracticeCallbackStatus;
import com.team.dating_backend.aipractice.enums.AiPracticeChatStatus;
import com.team.dating_backend.aipractice.enums.AiPracticeErrorCode;
import com.team.dating_backend.aipractice.exception.AiPracticeBusinessException;
import com.team.dating_backend.aipractice.repository.AiPracticeChatRepository;
import com.team.dating_backend.aipractice.repository.AiPracticeSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AiPracticeGenerationCallbackService {

    private final AiPracticeSessionRepository sessionRepository;
    private final AiPracticeChatRepository chatRepository;
    private final AiPracticeEndCommandQueue endCommandQueue;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public void receive(AiPracticeGenerationCallbackRequest request) {
        AiPracticeSession session = sessionRepository.findByIdForUpdate(request.practiceSessionId())
            .orElseThrow(() -> new AiPracticeBusinessException(AiPracticeErrorCode.AI_CALLBACK_INVALID));
        AiPracticeChat chat = chatRepository.findByIdAndSession_Id(
            request.chatId(), request.practiceSessionId())
            .orElseThrow(() -> new AiPracticeBusinessException(AiPracticeErrorCode.AI_CALLBACK_INVALID));
        if (chat.getGenerationAttempt() != request.generationAttempt()) {
            endCommandQueue.enqueueIfReady(session);
            return;
        }
        attachAiSessionId(session, request.aiSessionId());
        if (chat.getStatus() == AiPracticeChatStatus.COMPLETED) {
            return;
        }
        if (chat.getStatus() == AiPracticeChatStatus.FAILED
            && request.status() == AiPracticeCallbackStatus.FAILED) {
            endCommandQueue.enqueueIfReady(session);
            return;
        }

        if (request.status() == AiPracticeCallbackStatus.COMPLETED
            && request.aiResponse() != null && !request.aiResponse().isBlank()) {
            chat.complete(request.aiResponse(), AiPracticeTime.now());
        } else {
            chat.fail("AI_GENERATION_FAILED");
        }

        eventPublisher.publishEvent(new AiPracticeChatUpdatedEvent(
            session.getUserId(),
            session.getId(),
            chat.getId(),
            chat.getStatus(),
            chat.getAiResponse(),
            chat.getFailureCode(),
            chat.getCompletedAt()));
        endCommandQueue.enqueueIfReady(session);
    }

    private void attachAiSessionId(AiPracticeSession session, String aiSessionId) {
        try {
            session.attachAiSessionId(aiSessionId);
        } catch (IllegalStateException exception) {
            throw new AiPracticeBusinessException(
                AiPracticeErrorCode.AI_SESSION_ID_CONFLICT, exception);
        }
    }
}
