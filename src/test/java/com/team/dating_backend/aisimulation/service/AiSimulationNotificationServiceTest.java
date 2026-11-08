package com.team.dating_backend.aisimulation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;

import com.team.dating_backend.chat.repository.ChatRoomRepository;
import com.team.dating_backend.aisimulation.entity.AiSimulationSession;
import com.team.dating_backend.aisimulation.enums.AiSimulationErrorCode;
import com.team.dating_backend.aisimulation.enums.AiSimulationNotificationResult;
import com.team.dating_backend.aisimulation.enums.AiSimulationNotificationRecipient;
import com.team.dating_backend.aisimulation.enums.AiSimulationSessionStatus;
import com.team.dating_backend.aisimulation.exception.AiSimulationBusinessException;
import com.team.dating_backend.aisimulation.repository.AiSimulationSessionRepository;
import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.notification.service.NotificationRequestService;
import com.team.dating_backend.recommendation.entity.RecommendationPass;
import com.team.dating_backend.recommendation.repository.RecommendationPassRepository;
import com.team.dating_backend.user.enums.UserStatus;
import com.team.dating_backend.user.repository.UserBlockRepository;
import com.team.dating_backend.user.repository.UserRepository;
import java.util.Optional;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;

class AiSimulationNotificationServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final UserBlockRepository blocks = mock(UserBlockRepository.class);
    private final ChatRoomRepository rooms = mock(ChatRoomRepository.class);
    private final RecommendationPassRepository passes = mock(RecommendationPassRepository.class);
    private final NotificationRequestService notifications = mock(NotificationRequestService.class);
    private final AiSimulationSessionRepository sessions = mock(AiSimulationSessionRepository.class);
    private final AiSimulationNotificationService service = new AiSimulationNotificationService(users, blocks, rooms,
        passes, notifications, sessions);

    @BeforeEach
    void setUp() {
        given(users.existsByIdAndStatusNot(any(), org.mockito.ArgumentMatchers.eq(UserStatus.WITHDRAWN)))
            .willReturn(true);
        given(users.existsByIdAndStatus(1L, UserStatus.ACTIVE)).willReturn(true);
    }

    @Test
    void 세션에서_참여자를_읽고_기존_문구와_세션_생성_시각으로_요청을_기록한다() {
        var createdAt = LocalDateTime.of(2026, 11, 7, 10, 5);
        given(sessions.findByIdForUpdate(100L))
            .willReturn(Optional.of(new AiSimulationSession(1L, 2L, createdAt)));

        assertThat(service.requested(100L)).isEqualTo(AiSimulationNotificationResult.RECORDED);

        var request = ArgumentCaptor.forClass(NotificationCreateRequested.class);
        verify(notifications).requestNotification(request.capture());
        assertThat(request.getValue().sourceEventId()).isEqualTo("AI_SIMULATION_REQUESTED:100");
        assertThat(request.getValue().recipientUserId()).isEqualTo(2L);
        assertThat(request.getValue().type()).isEqualTo(NotificationType.AI_SIMULATION_REQUESTED);
        assertThat(request.getValue().targetId()).isNull();
        assertThat(request.getValue().targetType()).isNull();
        assertThat(request.getValue().title()).isEqualTo("시뮬레이션 요청");
        assertThat(request.getValue().body()).isEqualTo("누군가 회원님과의 AI 소개팅을 시작했어요");
        assertThat(request.getValue().requestedAt()).isEqualTo(createdAt.toInstant(ZoneOffset.UTC));
    }

    @Test
    void 이미_완료된_세션의_늦은_요청_알림은_생략한다() {
        var session = new AiSimulationSession(1L, 2L, LocalDateTime.now());
        session.complete(LocalDateTime.now());
        given(sessions.findByIdForUpdate(100L)).willReturn(Optional.of(session));

        assertThat(service.requested(100L)).isEqualTo(AiSimulationNotificationResult.SKIPPED);

        verifyNoInteractions(notifications);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pass", "block", "withdrawn", "left"})
    void Kafka_요청도_기존_수신_제외_정책을_적용한다(String policy) {
        given(sessions.findByIdForUpdate(100L))
            .willReturn(Optional.of(new AiSimulationSession(1L, 2L, LocalDateTime.now())));
        switch (policy) {
            case "pass" -> given(passes.findByPasserUserIdAndPassedUserId(2L, 1L))
                .willReturn(Optional.of(mock(RecommendationPass.class)));
            case "block" -> given(blocks.existsActiveBlockBetween(1L, 2L)).willReturn(true);
            case "withdrawn" -> given(users.existsByIdAndStatusNot(2L, UserStatus.WITHDRAWN)).willReturn(false);
            case "left" -> given(rooms.existsLeftParticipantBetweenUsers(1L, 2L)).willReturn(true);
            default -> throw new IllegalArgumentException(policy);
        }

        assertThat(service.requested(100L)).isEqualTo(AiSimulationNotificationResult.SKIPPED);

        verifyNoInteractions(notifications);
    }

    @Test
    void 존재하지_않는_세션은_조회_오류를_전달한다() {
        given(sessions.findByIdForUpdate(100L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.requested(100L))
            .isInstanceOfSatisfying(AiSimulationBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(AiSimulationErrorCode.SIMULATION_NOT_FOUND));
        verifyNoInteractions(notifications);
    }

    @Test
    void 상대_ID가_없는_세션은_에러코드를_전달한다() {
        var session = new AiSimulationSession(1L, 2L, LocalDateTime.now());
        ReflectionTestUtils.setField(session, "targetUserId", null);
        given(sessions.findByIdForUpdate(100L)).willReturn(Optional.of(session));

        assertThatThrownBy(() -> service.requested(100L))
            .isInstanceOfSatisfying(AiSimulationBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(AiSimulationErrorCode.SIMULATION_TARGET_USER_MISSING));
        verifyNoInteractions(notifications);
    }

    @Test
    void 완료_이벤트는_저장된_참여자와_완료_시각으로_기존_알림을_기록한다() {
        var session = completedSession();
        given(sessions.findById(100L)).willReturn(Optional.of(session));

        for (var recipient : AiSimulationNotificationRecipient.values()) {
            assertThat(service.completed(100L, recipient)).isEqualTo(AiSimulationNotificationResult.RECORDED);
        }

        var requests = ArgumentCaptor.forClass(NotificationCreateRequested.class);
        verify(notifications, times(2)).requestNotification(requests.capture());
        assertThat(requests.getAllValues()).extracting(NotificationCreateRequested::sourceEventId)
            .containsExactly("AI_SIMULATION_COMPLETED:100", "AI_SIMULATION_COMPLETED:100:2");
        assertThat(requests.getAllValues()).extracting(NotificationCreateRequested::recipientUserId)
            .containsExactly(1L, 2L);
        assertThat(requests.getAllValues()).allSatisfy(request -> {
            assertThat(request.type()).isEqualTo(NotificationType.AI_SIMULATION_REPORT_COMPLETED);
            assertThat(request.targetType()).isEqualTo("AI_SIMULATION");
            assertThat(request.targetId()).isEqualTo(100L);
            assertThat(request.requestedAt()).isEqualTo(session.getFinishedAt().toInstant(ZoneOffset.UTC));
        });
    }

    @ParameterizedTest
    @EnumSource(value = AiSimulationSessionStatus.class, names = {"IN_PROGRESS", "FAILED", "CANCELED"})
    void 완료되지_않은_세션의_완료_알림은_양쪽_모두_생략한다(AiSimulationSessionStatus status) {
        var session = new AiSimulationSession(1L, 2L, LocalDateTime.now());
        ReflectionTestUtils.setField(session, "status", status);
        given(sessions.findById(100L)).willReturn(Optional.of(session));

        for (var recipient : AiSimulationNotificationRecipient.values()) {
            assertThat(service.completed(100L, recipient)).isEqualTo(AiSimulationNotificationResult.SKIPPED);
        }
        verifyNoInteractions(notifications);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pass", "block", "withdrawn", "left"})
    void 상대방_제외_정책이_요청자의_완료_알림에_영향을_주지_않는다(String policy) {
        given(sessions.findById(100L)).willReturn(Optional.of(completedSession()));
        switch (policy) {
            case "pass" -> given(passes.findByPasserUserIdAndPassedUserId(2L, 1L))
                .willReturn(Optional.of(mock(RecommendationPass.class)));
            case "block" -> given(blocks.existsActiveBlockBetween(1L, 2L)).willReturn(true);
            case "withdrawn" -> given(users.existsByIdAndStatusNot(2L, UserStatus.WITHDRAWN)).willReturn(false);
            case "left" -> given(rooms.existsLeftParticipantBetweenUsers(1L, 2L)).willReturn(true);
            default -> throw new IllegalArgumentException(policy);
        }

        assertThat(service.completed(100L, AiSimulationNotificationRecipient.REQUESTER))
            .isEqualTo(AiSimulationNotificationResult.RECORDED);
        assertThat(service.completed(100L, AiSimulationNotificationRecipient.TARGET))
            .isEqualTo(AiSimulationNotificationResult.SKIPPED);
        verify(notifications, times(1)).requestNotification(any());
    }

    @Test
    void 비활성_요청자의_완료_알림은_생략한다() {
        given(sessions.findById(100L)).willReturn(Optional.of(completedSession()));
        given(users.existsByIdAndStatus(1L, UserStatus.ACTIVE)).willReturn(false);

        assertThat(service.completed(100L, AiSimulationNotificationRecipient.REQUESTER))
            .isEqualTo(AiSimulationNotificationResult.SKIPPED);
        verifyNoInteractions(notifications);
    }

    @Test
    void 상대_ID가_없어도_요청자_알림을_처리하고_상대방_알림은_오류로_전달한다() {
        var session = completedSession();
        ReflectionTestUtils.setField(session, "targetUserId", null);
        given(sessions.findById(100L)).willReturn(Optional.of(session));

        assertThat(service.completed(100L, AiSimulationNotificationRecipient.REQUESTER))
            .isEqualTo(AiSimulationNotificationResult.RECORDED);
        assertThatThrownBy(() -> service.completed(100L, AiSimulationNotificationRecipient.TARGET))
            .isInstanceOfSatisfying(AiSimulationBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(AiSimulationErrorCode.SIMULATION_TARGET_USER_MISSING));
    }

    @Test
    void 완료_시각이_없는_완료_세션은_알림을_기록하지_않고_에러코드를_전달한다() {
        var session = completedSession();
        ReflectionTestUtils.setField(session, "finishedAt", null);
        given(sessions.findById(100L)).willReturn(Optional.of(session));

        assertThatThrownBy(() -> service.completed(100L, AiSimulationNotificationRecipient.REQUESTER))
            .isInstanceOfSatisfying(AiSimulationBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(AiSimulationErrorCode.SIMULATION_EVENT_INVALID));
        verifyNoInteractions(notifications);
    }

    private AiSimulationSession completedSession() {
        var session = new AiSimulationSession(1L, 2L, LocalDateTime.of(2026, 11, 7, 10, 5));
        session.complete(session.getCreatedAt().plusMinutes(1));
        return session;
    }
}
