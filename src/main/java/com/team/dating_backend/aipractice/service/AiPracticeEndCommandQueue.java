package com.team.dating_backend.aipractice.service;

import com.team.dating_backend.aipractice.entity.AiPracticeOutbox;
import com.team.dating_backend.aipractice.entity.AiPracticeSession;
import com.team.dating_backend.aipractice.enums.AiPracticeChatStatus;
import com.team.dating_backend.aipractice.enums.AiPracticeOutboxCommandType;
import com.team.dating_backend.aipractice.enums.AiPracticeSessionStatus;
import com.team.dating_backend.aipractice.repository.AiPracticeChatRepository;
import com.team.dating_backend.aipractice.repository.AiPracticeOutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AiPracticeEndCommandQueue {

    private final AiPracticeChatRepository chatRepository;
    private final AiPracticeOutboxRepository outboxRepository;

    public void enqueueIfReady(AiPracticeSession session) {
        if (session.getStatus() != AiPracticeSessionStatus.ENDED
            || session.getAiSessionId() == null
            || chatRepository.existsBySession_IdAndStatus(
                session.getId(), AiPracticeChatStatus.GENERATING)
            || !session.markEndCommandEnqueued()) {
            return;
        }

        outboxRepository.save(new AiPracticeOutbox(
            AiPracticeOutboxCommandType.END_SESSION,
            session.getId(),
            null,
            0,
            AiPracticeTime.now()));
    }
}
