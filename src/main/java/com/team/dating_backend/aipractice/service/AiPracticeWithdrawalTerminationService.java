package com.team.dating_backend.aipractice.service;

import com.team.dating_backend.aipractice.entity.AiPracticeSession;
import com.team.dating_backend.aipractice.enums.AiPracticeChatStatus;
import com.team.dating_backend.aipractice.enums.AiPracticeFailureCode;
import com.team.dating_backend.aipractice.enums.AiPracticeSessionStatus;
import com.team.dating_backend.aipractice.repository.AiPracticeChatRepository;
import com.team.dating_backend.aipractice.repository.AiPracticeSessionRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AiPracticeWithdrawalTerminationService {

    private final AiPracticeSessionRepository sessionRepository;
    private final AiPracticeChatRepository chatRepository;
    private final AiPracticeEndCommandQueue endCommandQueue;

    @Transactional(propagation = Propagation.MANDATORY)
    public void terminateForUser(Long userId, LocalDateTime endedAt) {
        for (AiPracticeSession session : sessionRepository.findAllForUserByStatusForUpdate(
            userId, AiPracticeSessionStatus.ACTIVE)) {
            chatRepository.findAllBySession_IdAndStatus(
                session.getId(), AiPracticeChatStatus.GENERATING)
                .forEach(chat -> chat.fail(AiPracticeFailureCode.USER_WITHDRAWN.name()));
            session.end(endedAt);
            endCommandQueue.enqueueIfReady(session);
        }
    }
}
