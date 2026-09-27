package com.team.dating_backend.aipractice.service;

import com.team.dating_backend.aipractice.dto.response.AiPracticeSessionResponse;
import com.team.dating_backend.aipractice.entity.AiPracticeSession;
import com.team.dating_backend.aipractice.enums.AiPracticeErrorCode;
import com.team.dating_backend.aipractice.exception.AiPracticeBusinessException;
import com.team.dating_backend.aipractice.repository.AiPracticeSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AiPracticeSessionEndService {

    private final AiPracticeSessionRepository sessionRepository;
    private final AiPracticeEndCommandQueue endCommandQueue;
    private final AiPracticeSessionService sessionService;

    @Transactional
    public AiPracticeSessionResponse end(Long userId, Long sessionId) {
        AiPracticeSession session = sessionRepository.findByIdAndUserIdForUpdate(sessionId, userId)
            .orElseThrow(() -> new AiPracticeBusinessException(AiPracticeErrorCode.SESSION_NOT_FOUND));
        session.end(AiPracticeTime.now());
        endCommandQueue.enqueueIfReady(session);
        return sessionService.toResponse(session, false);
    }
}
