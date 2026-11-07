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
    void 요청_알림은_B에게_이동_대상_없이_전송한다() {
        service.requested(100L, 1L, 2L);
        var captor = ArgumentCaptor.forClass(NotificationCreateRequested.class);
        verify(notifications).requestNotification(captor.capture());
        assertThat(captor.getValue().recipientUserId()).isEqualTo(2L);
        assertThat(captor.getValue().type()).isEqualTo(NotificationType.AI_SIMULATION_REQUESTED);
        assertThat(captor.getValue().targetId()).isNull();
        assertThat(captor.getValue().targetType()).isNull();
    }

    @Test
    void B가_A를_패스했다면_B의_요청과_완료_알림은_없지만_A의_완료_알림은_있다() {
        given(passes.findByPasserUserIdAndPassedUserId(2L, 1L))
            .willReturn(Optional.of(mock(RecommendationPass.class)));
        service.requested(100L, 1L, 2L);
        service.completed(100L, 1L, 2L, 2L);
        verifyNoInteractions(notifications);
        service.completed(100L, 1L, 2L, 1L);
        var captor = ArgumentCaptor.forClass(NotificationCreateRequested.class);
        verify(notifications).requestNotification(captor.capture());
        assertThat(captor.getValue().recipientUserId()).isEqualTo(1L);
        assertThat(captor.getValue().targetId()).isEqualTo(100L);
    }

    @Test
    void 차단_탈퇴_방나가기는_B의_요청과_완료_알림을_제외하지만_A의_완료_알림은_유지한다() {
        given(blocks.existsActiveBlockBetween(1L, 2L)).willReturn(true);
        service.requested(100L, 1L, 2L);
        service.completed(100L, 1L, 2L, 1L);
        service.completed(100L, 1L, 2L, 2L);
        given(blocks.existsActiveBlockBetween(1L, 2L)).willReturn(false);
        given(rooms.existsLeftParticipantBetweenUsers(1L, 2L)).willReturn(true);
        service.requested(100L, 1L, 2L);
        service.completed(100L, 1L, 2L, 1L);
        service.completed(100L, 1L, 2L, 2L);
        given(rooms.existsLeftParticipantBetweenUsers(1L, 2L)).willReturn(false);
        given(users.existsByIdAndStatusNot(2L, UserStatus.WITHDRAWN)).willReturn(false);
        service.requested(100L, 1L, 2L);
        service.completed(100L, 1L, 2L, 1L);
        service.completed(100L, 1L, 2L, 2L);
        var captor = ArgumentCaptor.forClass(NotificationCreateRequested.class);
        verify(notifications, times(3)).requestNotification(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(request -> {
            assertThat(request.recipientUserId()).isEqualTo(1L);
            assertThat(request.type()).isEqualTo(NotificationType.AI_SIMULATION_REPORT_COMPLETED);
        });
    }

    @Test
    void A가_활성_상태가_아니면_자신의_완료_알림은_요청하지_않는다() {
        given(users.existsByIdAndStatus(1L, UserStatus.ACTIVE)).willReturn(false);
        service.completed(100L, 1L, 2L, 1L);
        verifyNoInteractions(notifications);
    }

    @Test
    void 정상_완료시_양쪽에게_각각_완료_알림을_요청한다() {
        service.completed(100L, 1L, 2L, 1L);
        service.completed(100L, 1L, 2L, 2L);
        var captor = ArgumentCaptor.forClass(NotificationCreateRequested.class);
        verify(notifications, times(2)).requestNotification(captor.capture());
        assertThat(captor.getAllValues()).extracting(NotificationCreateRequested::recipientUserId)
            .containsExactly(1L, 2L);
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
        assertThat(request.getValue().title()).isEqualTo("시뮬레이션 요청");
        assertThat(request.getValue().body()).isEqualTo("누군가 회원님과의 AI 시뮬레이션을 진행 중이에요.");
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
}
