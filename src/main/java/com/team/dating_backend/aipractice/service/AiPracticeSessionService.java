package com.team.dating_backend.aipractice.service;

import com.team.dating_backend.aipractice.dto.request.AiPracticeSessionCreateRequest;
import com.team.dating_backend.aipractice.dto.response.AiPracticeSessionResponse;
import com.team.dating_backend.aipractice.entity.AiPracticeSession;
import com.team.dating_backend.aipractice.enums.AiPracticeErrorCode;
import com.team.dating_backend.aipractice.enums.AiPracticeSessionStatus;
import com.team.dating_backend.aipractice.exception.AiPracticeBusinessException;
import com.team.dating_backend.aipractice.repository.AiPracticeSessionRepository;
import com.team.dating_backend.user.repository.UserBlockRepository;
import com.team.dating_backend.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AiPracticeSessionService {

    private final AiPracticeSessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final UserBlockRepository userBlockRepository;

    @Transactional
    public AiPracticeSessionResponse startOrResume(
        Long userId, AiPracticeSessionCreateRequest request) {
        userRepository.findByIdForUpdate(userId)
            .orElseThrow(() -> new AiPracticeBusinessException(AiPracticeErrorCode.USER_NOT_FOUND));

        Long targetMemberId = request.targetMemberId();
        if (targetMemberId.equals(userId)) {
            throw new AiPracticeBusinessException(AiPracticeErrorCode.INVALID_TARGET_MEMBER);
        }
        if (!userRepository.existsById(targetMemberId)) {
            throw new AiPracticeBusinessException(AiPracticeErrorCode.TARGET_MEMBER_NOT_FOUND);
        }
        if (userBlockRepository.existsActiveBlockBetween(userId, targetMemberId)) {
            throw new AiPracticeBusinessException(AiPracticeErrorCode.TARGET_MEMBER_NOT_FOUND);
        }

        AiPracticeSession activeSession = sessionRepository
            .findFirstByUserIdAndTargetMemberIdAndStatusOrderByIdDesc(
                userId, targetMemberId, AiPracticeSessionStatus.ACTIVE)
            .orElse(null);
        if (activeSession != null) {
            return toResponse(activeSession, true);
        }

        AiPracticeSession session = sessionRepository.save(
            new AiPracticeSession(userId, targetMemberId, AiPracticeTime.now()));
        return toResponse(session, false);
    }

    @Transactional(readOnly = true)
    public AiPracticeSession requireOwnedSession(Long sessionId, Long userId) {
        return sessionRepository.findAccessibleByIdAndUserId(sessionId, userId)
            .orElseThrow(() -> new AiPracticeBusinessException(AiPracticeErrorCode.SESSION_NOT_FOUND));
    }

    public AiPracticeSessionResponse toResponse(AiPracticeSession session, boolean resumed) {
        return new AiPracticeSessionResponse(
            session.getId(),
            session.getTargetMemberId(),
            session.getStatus(),
            session.getStartedAt(),
            session.getEndedAt(),
            resumed);
    }

}
