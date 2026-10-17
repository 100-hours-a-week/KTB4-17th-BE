package com.team.dating_backend.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.dto.event.NotificationCreatedEvent;
import com.team.dating_backend.notification.dto.response.NotificationEventResponse;
import com.team.dating_backend.notification.entity.Notification;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.repository.NotificationRepository;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.repository.UserRepository;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

class NotificationCreateServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long NOTIFICATION_ID = 10L;

    private UserRepository userRepository;
    private NotificationRepository repository;
    private ApplicationEventPublisher eventPublisher;
    private NotificationCreateService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        repository = mock(NotificationRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new NotificationCreateService(userRepository, repository, eventPublisher);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 전달한_정보로_미읽음_알림을_저장하고_저장된_ID를_포함한_이벤트를_발행한다(boolean hasTarget) {
        stubRecipient();
        stubSave();
        String targetType = hasTarget ? "CHAT_ROOM" : null;
        Long targetId = hasTarget ? 30L : null;

        service.createNotification(USER_ID, NotificationType.MATCH,
            "새로운 매칭", "매칭이 성사되었어요.", targetType, targetId);

        ArgumentCaptor<Notification> notificationCaptor = ArgumentCaptor.forClass(Notification.class);
        verify(repository).save(notificationCaptor.capture());
        Notification saved = notificationCaptor.getValue();
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getType()).isEqualTo(NotificationType.MATCH);
        assertThat(saved.getTitle()).isEqualTo("새로운 매칭");
        assertThat(saved.getBody()).isEqualTo("매칭이 성사되었어요.");
        assertThat(saved.getTargetType()).isEqualTo(targetType);
        assertThat(saved.getTargetId()).isEqualTo(targetId);
        assertThat(saved.getReadAt()).isNull();
        assertThat(saved.getDeletedAt()).isNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        verify(eventPublisher).publishEvent(new NotificationCreatedEvent(
            USER_ID, NotificationEventResponse.from(saved)));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0L, -1L})
    void 수신_회원_ID가_유효하지_않으면_저장과_이벤트_발행_전에_실패한다(Long recipientUserId) {
        assertThatThrownBy(() -> service.createNotification(
            recipientUserId, NotificationType.NOTICE, "공지", "공지 본문", null, null))
            .isInstanceOfSatisfying(NotificationBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(CommonErrorCode.INTERNAL_SERVER_ERROR));

        verifyNoInteractions(userRepository, repository, eventPublisher);
    }

    @ParameterizedTest
    @MethodSource("invalidContents")
    void 알림_정보가_유효하지_않으면_저장하거나_이벤트를_발행하지_않는다(
        NotificationType type, String targetType, Long targetId) {
        stubRecipient();

        assertThatThrownBy(() -> service.createNotification(
            USER_ID, type, "공지", "공지 본문", targetType, targetId))
            .isInstanceOfSatisfying(NotificationBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(CommonErrorCode.INTERNAL_SERVER_ERROR));

        verifyNoInteractions(repository, eventPublisher);
    }

    @Test
    void 저장_실패는_원래_예외를_전달하고_이벤트를_발행하지_않는다() {
        stubRecipient();
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("저장 실패");
        given(repository.save(any(Notification.class))).willThrow(failure);

        assertThatThrownBy(() -> service.createNotification(
            USER_ID, NotificationType.NOTICE, "공지", "공지 본문", null, null))
            .isSameAs(failure);

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void 이벤트_발행_실패를_성공으로_처리하지_않고_원래_예외를_전달한다() {
        stubRecipient();
        stubSave();
        IllegalStateException failure = new IllegalStateException("이벤트 발행 실패");
        willThrow(failure).given(eventPublisher).publishEvent(any(NotificationCreatedEvent.class));

        assertThatThrownBy(() -> service.createNotification(
            USER_ID, NotificationType.NOTICE, "공지", "공지 본문", null, null))
            .isSameAs(failure);
    }

    private void stubRecipient() {
        User recipient = mock(User.class);
        given(recipient.getId()).willReturn(USER_ID);
        given(userRepository.getReferenceById(USER_ID)).willReturn(recipient);
    }

    private void stubSave() {
        given(repository.save(any(Notification.class))).willAnswer(invocation -> {
            Notification notification = invocation.getArgument(0);
            ReflectionTestUtils.setField(notification, "id", NOTIFICATION_ID);
            return notification;
        });
    }

    private static Stream<Arguments> invalidContents() {
        return Stream.of(
            Arguments.of(null, null, null),
            Arguments.of(NotificationType.NOTICE, "CHAT_ROOM", null),
            Arguments.of(NotificationType.NOTICE, null, 30L));
    }
}
