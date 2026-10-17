package com.team.dating_backend.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.CannotAcquireLockException;

class NotificationReadServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long NOTIFICATION_ID = 10L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 17, 12, 0);

    private NotificationAccessService accessService;
    private NotificationRepository repository;
    private NotificationReadService service;

    @BeforeEach
    void setUp() {
        accessService = mock(NotificationAccessService.class);
        repository = mock(NotificationRepository.class);
        service = new NotificationReadService(accessService, repository);
    }

    @Test
    void 접근_검증이_실패하면_알림_잠금_조회를_수행하지_않는다() {
        willThrow(new NotificationBusinessException(NotificationErrorCode.REQUESTER_NOT_ACTIVE))
            .given(accessService).assertActiveUser(USER_ID);

        assertReadFailure(NotificationErrorCode.REQUESTER_NOT_ACTIVE);
        verifyNoInteractions(repository);
    }

    @Test
    void 존재하지_않는_알림은_404_오류로_처리한다() {
        given(repository.findByIdForUpdate(NOTIFICATION_ID)).willReturn(Optional.empty());

        assertReadFailure(NotificationErrorCode.NOTIFICATION_NOT_FOUND);
    }

    @ParameterizedTest
    @CsvSource({"1,false", "1,true", "2,false", "2,true"})
    void 삭제된_알림은_소유권과_읽음_여부보다_먼저_404로_처리한다(Long ownerId, boolean read) {
        Notification notification = notification(ownerId);
        if (read) {
            notification.markAsRead(NOW.plusMinutes(1));
        }
        notification.markAsDeleted(NOW.plusMinutes(2));
        LocalDateTime originalReadAt = notification.getReadAt();
        given(repository.findByIdForUpdate(NOTIFICATION_ID)).willReturn(Optional.of(notification));

        assertReadFailure(NotificationErrorCode.NOTIFICATION_NOT_FOUND);
        assertThat(notification.getReadAt()).isEqualTo(originalReadAt);
        assertThat(notification.getDeletedAt()).isEqualTo(NOW.plusMinutes(2));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 타인의_미삭제_알림은_이미_읽었어도_403으로_차단한다(boolean read) {
        Notification notification = notification(2L);
        if (read) {
            notification.markAsRead(NOW.plusMinutes(1));
        }
        LocalDateTime originalReadAt = notification.getReadAt();
        given(repository.findByIdForUpdate(NOTIFICATION_ID)).willReturn(Optional.of(notification));

        assertReadFailure(NotificationErrorCode.NOTIFICATION_ACCESS_DENIED);
        assertThat(notification.getReadAt()).isEqualTo(originalReadAt);
        assertThat(notification.getDeletedAt()).isNull();
    }

    @Test
    void 본인의_이미_읽은_알림은_최초_시각을_유지하고_추가_DB_작업을_수행하지_않는다() {
        Notification notification = notification(USER_ID);
        notification.markAsRead(NOW);
        given(repository.findByIdForUpdate(NOTIFICATION_ID)).willReturn(Optional.of(notification));

        service.markAsRead(USER_ID, NOTIFICATION_ID);

        assertThat(notification.getReadAt()).isEqualTo(NOW);
        verify(repository).findByIdForUpdate(NOTIFICATION_ID);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void 잠금_실패를_알림_없음으로_바꾸지_않고_원래_예외를_전달한다() {
        CannotAcquireLockException failure = new CannotAcquireLockException("잠금 실패");
        given(repository.findByIdForUpdate(NOTIFICATION_ID)).willThrow(failure);

        assertThatThrownBy(() -> service.markAsRead(USER_ID, NOTIFICATION_ID)).isSameAs(failure);
    }

    private void assertReadFailure(NotificationErrorCode errorCode) {
        assertThatThrownBy(() -> service.markAsRead(USER_ID, NOTIFICATION_ID))
            .isInstanceOfSatisfying(NotificationBusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }

    private Notification notification(Long ownerId) {
        User owner = mock(User.class);
        given(owner.getId()).willReturn(ownerId);
        return new Notification(owner, NotificationType.LIKE, "관심", "관심 도착", null, null, NOW);
    }
}
