package com.team.dating_backend.aisimulation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;

import com.team.dating_backend.chat.repository.ChatRoomRepository;
import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.notification.service.NotificationRequestService;
import com.team.dating_backend.recommendation.entity.RecommendationPass;
import com.team.dating_backend.recommendation.repository.RecommendationPassRepository;
import com.team.dating_backend.user.enums.UserStatus;
import com.team.dating_backend.user.repository.UserBlockRepository;
import com.team.dating_backend.user.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AiSimulationNotificationServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final UserBlockRepository blocks = mock(UserBlockRepository.class);
    private final ChatRoomRepository rooms = mock(ChatRoomRepository.class);
    private final RecommendationPassRepository passes = mock(RecommendationPassRepository.class);
    private final NotificationRequestService notifications = mock(NotificationRequestService.class);
    private final AiSimulationNotificationService service = new AiSimulationNotificationService(users, blocks, rooms,
        passes, notifications);

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
}
