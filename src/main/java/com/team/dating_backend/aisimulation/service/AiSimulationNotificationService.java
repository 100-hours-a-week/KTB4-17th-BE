package com.team.dating_backend.aisimulation.service;

import com.team.dating_backend.aisimulation.enums.AiSimulationErrorCode;
import com.team.dating_backend.aisimulation.enums.AiSimulationNotificationRecipient;
import com.team.dating_backend.aisimulation.enums.AiSimulationNotificationResult;
import com.team.dating_backend.aisimulation.enums.AiSimulationSessionStatus;
import com.team.dating_backend.aisimulation.exception.AiSimulationBusinessException;
import com.team.dating_backend.aisimulation.repository.AiSimulationSessionRepository;
import com.team.dating_backend.chat.repository.ChatRoomRepository;
import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.enums.AiNotificationTemplate;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.notification.service.NotificationRequestService;
import com.team.dating_backend.recommendation.repository.RecommendationPassRepository;
import com.team.dating_backend.user.enums.UserStatus;
import com.team.dating_backend.user.repository.UserBlockRepository;
import com.team.dating_backend.user.repository.UserRepository;
import java.time.Instant;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AiSimulationNotificationService {
    private final UserRepository users;
    private final UserBlockRepository blocks;
    private final ChatRoomRepository rooms;
    private final RecommendationPassRepository passes;
    private final NotificationRequestService notifications;
    private final AiSimulationSessionRepository sessions;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AiSimulationNotificationResult requested(Long sessionId) {
        var session = sessions.findByIdForUpdate(sessionId)
            .orElseThrow(() -> new AiSimulationBusinessException(AiSimulationErrorCode.SIMULATION_NOT_FOUND));
        if (session.getStatus() == AiSimulationSessionStatus.COMPLETED) {
            return AiSimulationNotificationResult.SKIPPED;
        }
        if (session.getTargetUserId() == null) {
            throw new AiSimulationBusinessException(AiSimulationErrorCode.SIMULATION_TARGET_USER_MISSING);
        }
        return recordRequested(sessionId, session.getUserId(), session.getTargetUserId(),
            session.getCreatedAt().toInstant(ZoneOffset.UTC));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void requested(Long sessionId, Long requesterId, Long targetId) {
        recordRequested(sessionId, requesterId, targetId, Instant.now());
    }

    private AiSimulationNotificationResult recordRequested(Long sessionId, Long requesterId, Long targetId,
        Instant requestedAt) {
        if (!eligiblePair(requesterId, targetId) || passed(requesterId, targetId)) {
            return AiSimulationNotificationResult.SKIPPED;
        }
        notifications.requestNotification(new NotificationCreateRequested(
            NotificationCreateRequested.CURRENT_SCHEMA_VERSION, "AI_SIMULATION_REQUESTED:" + sessionId,
            targetId, NotificationType.AI_SIMULATION_REQUESTED, "시뮬레이션 요청",
            "누군가 회원님과의 AI 소개팅을 시작했어요", null, null, requestedAt));
        return AiSimulationNotificationResult.RECORDED;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completed(Long sessionId, Long requesterId, Long targetId, Long recipientId) {
        recordCompleted(sessionId, requesterId, targetId, recipientId, Instant.now());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AiSimulationNotificationResult completed(Long sessionId, AiSimulationNotificationRecipient recipient) {
        var session = sessions.findById(sessionId)
            .orElseThrow(() -> new AiSimulationBusinessException(AiSimulationErrorCode.SIMULATION_NOT_FOUND));
        if (session.getStatus() != AiSimulationSessionStatus.COMPLETED) {
            return AiSimulationNotificationResult.SKIPPED;
        }
        Long recipientId = recipient == AiSimulationNotificationRecipient.REQUESTER
            ? session.getUserId()
            : session.getTargetUserId();
        if (recipientId == null) {
            throw new AiSimulationBusinessException(AiSimulationErrorCode.SIMULATION_TARGET_USER_MISSING);
        }
        if (session.getFinishedAt() == null) {
            throw new AiSimulationBusinessException(AiSimulationErrorCode.SIMULATION_EVENT_INVALID);
        }
        return recordCompleted(sessionId, session.getUserId(), session.getTargetUserId(), recipientId,
            session.getFinishedAt().toInstant(ZoneOffset.UTC));
    }

    private AiSimulationNotificationResult recordCompleted(Long sessionId, Long requesterId, Long targetId,
        Long recipientId, Instant completedAt) {
        if (recipientId.equals(requesterId)) {
            if (!users.existsByIdAndStatus(requesterId, UserStatus.ACTIVE)) {
                return AiSimulationNotificationResult.SKIPPED;
            }
        } else if (!recipientId.equals(targetId) || !eligiblePair(requesterId, targetId)
            || passed(requesterId, targetId)) {
            return AiSimulationNotificationResult.SKIPPED;
        }
        var template = AiNotificationTemplate.SIMULATION_COMPLETED;
        notifications.requestNotification(new NotificationCreateRequested(
            NotificationCreateRequested.CURRENT_SCHEMA_VERSION,
            "AI_SIMULATION_COMPLETED:" + sessionId + (recipientId.equals(requesterId) ? "" : ":" + recipientId),
            recipientId, NotificationType.AI_SIMULATION_REPORT_COMPLETED,
            template.getTitle(), template.getBody(), template.getTargetType(), sessionId, completedAt));
        return AiSimulationNotificationResult.RECORDED;
    }

    private boolean passed(Long requesterId, Long targetId) {
        return passes.findByPasserUserIdAndPassedUserId(targetId, requesterId).isPresent();
    }

    private boolean eligiblePair(Long requesterId, Long targetId) {
        return users.existsByIdAndStatusNot(requesterId, UserStatus.WITHDRAWN)
            && users.existsByIdAndStatusNot(targetId, UserStatus.WITHDRAWN)
            && !blocks.existsActiveBlockBetween(requesterId, targetId)
            && !rooms.existsLeftParticipantBetweenUsers(requesterId, targetId);
    }
}
