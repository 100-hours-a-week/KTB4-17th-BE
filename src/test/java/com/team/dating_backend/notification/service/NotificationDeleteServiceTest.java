package com.team.dating_backend.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.team.dating_backend.notification.entity.Notification;
import com.team.dating_backend.notification.enums.NotificationErrorCode;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.repository.NotificationRepository;
import com.team.dating_backend.user.entity.User;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.CannotAcquireLockException;

class NotificationDeleteServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long NOTIFICATION_ID = 10L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 15, 12, 0);

    private NotificationAccessService accessService;
    private NotificationRepository repository;
    private NotificationDeleteService service;

    @BeforeEach
    void setUp() {
        accessService = mock(NotificationAccessService.class);
        repository = mock(NotificationRepository.class);
        service = new NotificationDeleteService(accessService, repository);
    }

    @Test
    void 접근_검증이_실패하면_알림을_조회하지_않는다() {
        willThrow(new NotificationBusinessException(NotificationErrorCode.REQUESTER_NOT_ACTIVE))
            .given(accessService).assertActiveUser(USER_ID);

        assertThatThrownBy(() -> service.deleteNotification(USER_ID, NOTIFICATION_ID))
            .isInstanceOfSatisfying(NotificationBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(NotificationErrorCode.REQUESTER_NOT_ACTIVE));
        verifyNoInteractions(repository);
    }

    @Test
    void 존재하지_않는_알림은_정상_종료한다() {
        given(repository.findByIdForUpdate(NOTIFICATION_ID)).willReturn(Optional.empty());

        assertThatCode(() -> service.deleteNotification(USER_ID, NOTIFICATION_ID))
            .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 다른_회원의_알림은_삭제_여부와_관계없이_거부한다(boolean deleted) {
        Notification notification = notification(2L);
        if (deleted) {
            notification.markAsDeleted(NOW.plusMinutes(1));
        }
        LocalDateTime originalDeletedAt = notification.getDeletedAt();
        given(repository.findByIdForUpdate(NOTIFICATION_ID))
            .willReturn(Optional.of(notification));

        assertThatThrownBy(() -> service.deleteNotification(USER_ID, NOTIFICATION_ID))
            .isInstanceOfSatisfying(NotificationBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(NotificationErrorCode.NOTIFICATION_ACCESS_DENIED));
        assertThat(notification.getDeletedAt()).isEqualTo(originalDeletedAt);
    }

    @Test
    void 본인의_이미_삭제된_알림은_기존_읽음과_삭제_시각을_유지한다() {
        Notification notification = notification(USER_ID);
        notification.markAsRead(NOW.plusMinutes(1));
        notification.markAsDeleted(NOW.plusMinutes(2));
        given(repository.findByIdForUpdate(NOTIFICATION_ID))
            .willReturn(Optional.of(notification));

        service.deleteNotification(USER_ID, NOTIFICATION_ID);

        assertThat(notification.getReadAt()).isEqualTo(NOW.plusMinutes(1));
        assertThat(notification.getDeletedAt()).isEqualTo(NOW.plusMinutes(2));
    }

    @Test
    void 잠금_실패를_알림_없음으로_바꾸지_않고_전달한다() {
        CannotAcquireLockException failure = new CannotAcquireLockException(null);
        given(repository.findByIdForUpdate(NOTIFICATION_ID)).willThrow(failure);

        assertThatThrownBy(() -> service.deleteNotification(USER_ID, NOTIFICATION_ID))
            .isSameAs(failure);
    }

    private Notification notification(Long recipientUserId) {
        User user = mock(User.class);
        given(user.getId()).willReturn(recipientUserId);
        return new Notification(user, NotificationType.LIKE,
            "관심", "관심 도착", null, null, NOW);
    }
}
