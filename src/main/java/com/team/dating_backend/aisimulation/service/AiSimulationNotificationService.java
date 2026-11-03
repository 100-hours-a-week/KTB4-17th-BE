package com.team.dating_backend.aisimulation.service;

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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void requested(Long sessionId, Long requesterId, Long targetId) {
        if (!eligiblePair(requesterId, targetId) || passed(requesterId, targetId)) return;
        notifications.requestNotification(new NotificationCreateRequested(
            NotificationCreateRequested.CURRENT_SCHEMA_VERSION, "AI_SIMULATION_REQUESTED:" + sessionId,
            targetId, NotificationType.AI_SIMULATION_REQUESTED, "시뮬레이션 요청",
            "누군가 회원님과의 AI 시뮬레이션을 진행 중이에요.", null, null, Instant.now()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completed(Long sessionId, Long requesterId, Long targetId, Long recipientId) {
        if (!eligiblePair(requesterId, targetId)
            || (recipientId.equals(targetId) && passed(requesterId, targetId))) return;
        var template = AiNotificationTemplate.SIMULATION_COMPLETED;
        notifications.requestNotification(new NotificationCreateRequested(
            NotificationCreateRequested.CURRENT_SCHEMA_VERSION,
            "AI_SIMULATION_COMPLETED:" + sessionId + (recipientId.equals(requesterId) ? "" : ":" + recipientId),
            recipientId, NotificationType.AI_SIMULATION_REPORT_COMPLETED,
            template.getTitle(), template.getBody(), template.getTargetType(), sessionId, Instant.now()));
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
