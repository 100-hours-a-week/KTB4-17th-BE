package com.team.dating_backend.aipractice.service;

import com.team.dating_backend.aipractice.dto.request.AiPracticeSessionCreateRequest;
import com.team.dating_backend.aipractice.dto.response.AiPracticeSessionPageResponse;
import com.team.dating_backend.aipractice.dto.response.AiPracticeSessionResponse;
import com.team.dating_backend.aipractice.entity.AiPracticeSession;
import com.team.dating_backend.aipractice.enums.AiPracticeErrorCode;
import com.team.dating_backend.aipractice.enums.AiPracticeSessionStatus;
import com.team.dating_backend.aipractice.exception.AiPracticeBusinessException;
import com.team.dating_backend.aipractice.repository.AiPracticeSessionRepository;
import com.team.dating_backend.user.repository.UserRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AiPracticeSessionService {

    private final AiPracticeSessionRepository sessionRepository;
    private final UserRepository userRepository;

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
    public AiPracticeSessionPageResponse listSessions(Long userId, Long cursor, int size) {
        List<AiPracticeSession> fetched = cursor == null
            ? sessionRepository.findByUserIdOrderByIdDesc(
                userId, PageRequest.of(0, size + 1))
            : sessionRepository.findByUserIdAndIdLessThanOrderByIdDesc(
                userId, cursor, PageRequest.of(0, size + 1));

        boolean hasNext = fetched.size() > size;
        List<AiPracticeSessionResponse> sessions = fetched.stream()
            .limit(size)
            .map(session -> toResponse(session, false))
            .toList();
        Long nextCursor = hasNext && !sessions.isEmpty()
            ? sessions.getLast().id()
            : null;
        return new AiPracticeSessionPageResponse(sessions, hasNext, nextCursor);
    }

    @Transactional(readOnly = true)
    public AiPracticeSession requireOwnedSession(Long sessionId, Long userId) {
        return sessionRepository.findByIdAndUserId(sessionId, userId)
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
