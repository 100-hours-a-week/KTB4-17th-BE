package com.team.dating_backend.aipractice.service;

import com.team.dating_backend.aipractice.dto.request.AiPracticeSessionCreateRequest;
import com.team.dating_backend.aipractice.dto.response.AiPracticeSessionResponse;
import com.team.dating_backend.aipractice.entity.AiPracticeSession;
import com.team.dating_backend.aipractice.enums.AiPracticeErrorCode;
import com.team.dating_backend.aipractice.enums.AiPracticeSessionStatus;
import com.team.dating_backend.aipractice.exception.AiPracticeBusinessException;
import com.team.dating_backend.aipractice.repository.AiPracticeSessionRepository;
import com.team.dating_backend.common.lock.repository.UserPairLockRepository;
import com.team.dating_backend.security.AuthenticationRequiredException;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.UserStatus;
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
    private final UserPairLockRepository userPairLockRepository;

    @Transactional
    public AiPracticeSessionResponse startOrResume(
        Long userId, AiPracticeSessionCreateRequest request) {
        Long targetUserId = request.targetUserId();
        if (targetUserId.equals(userId)) {
            throw new AiPracticeBusinessException(AiPracticeErrorCode.INVALID_TARGET_USER);
        }

        userPairLockRepository.acquire(userId, targetUserId);
        User user = userRepository.findByIdForUpdate(userId)
            .orElseThrow(() -> new AiPracticeBusinessException(AiPracticeErrorCode.USER_NOT_FOUND));
        if (user.getStatus() == UserStatus.WITHDRAWN) {
            throw new AuthenticationRequiredException();
        }
        User targetUser = userRepository.findByIdForUpdate(targetUserId)
            .orElseThrow(() -> new AiPracticeBusinessException(AiPracticeErrorCode.TARGET_USER_NOT_FOUND));
        if (targetUser.getStatus() == UserStatus.WITHDRAWN) {
            throw new AiPracticeBusinessException(AiPracticeErrorCode.TARGET_USER_NOT_FOUND);
        }
        if (userBlockRepository.existsActiveBlockBetween(userId, targetUserId)) {
            throw new AiPracticeBusinessException(AiPracticeErrorCode.TARGET_USER_NOT_FOUND);
        }

        AiPracticeSession activeSession = sessionRepository
            .findFirstByUserIdAndTargetUserIdAndStatusOrderByIdDesc(
                userId, targetUserId, AiPracticeSessionStatus.ACTIVE)
            .orElse(null);
        if (activeSession != null) {
            return toResponse(activeSession, true);
        }

        AiPracticeSession session = sessionRepository.save(
            new AiPracticeSession(userId, targetUserId, AiPracticeTime.now()));
        return toResponse(session, false);
    }

    @Transactional(readOnly = true)
    public AiPracticeSession requireOwnedSession(Long sessionId, Long userId) {
        return sessionRepository.findAccessibleByIdAndUserId(
            sessionId, userId, UserStatus.WITHDRAWN)
            .orElseThrow(() -> new AiPracticeBusinessException(AiPracticeErrorCode.SESSION_NOT_FOUND));
    }

    public AiPracticeSessionResponse toResponse(AiPracticeSession session, boolean resumed) {
        return new AiPracticeSessionResponse(
            session.getId(),
            session.getTargetUserId(),
            session.getStatus(),
            session.getStartedAt(),
            session.getEndedAt(),
            resumed);
    }

}
