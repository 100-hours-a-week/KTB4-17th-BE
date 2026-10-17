package com.team.dating_backend.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.team.dating_backend.notification.dto.response.NotificationListResponse;
import com.team.dating_backend.notification.entity.Notification;
import com.team.dating_backend.notification.enums.NotificationCategory;
import com.team.dating_backend.notification.enums.NotificationErrorCode;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.repository.NotificationRepository;
import com.team.dating_backend.user.entity.User;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

class NotificationListServiceTest {

    private static final Long USER_ID = 7L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 17, 12, 0);

    private NotificationAccessService accessService;
    private NotificationRepository repository;
    private NotificationListService service;

    @BeforeEach
    void setUp() {
        accessService = mock(NotificationAccessService.class);
        repository = mock(NotificationRepository.class);
        service = new NotificationListService(accessService, repository);
    }

    @Test
    void 접근_검증이_실패하면_알림을_조회하지_않는다() {
        willThrow(new NotificationBusinessException(NotificationErrorCode.REQUESTER_NOT_ACTIVE))
            .given(accessService).assertActiveUser(USER_ID);

        assertThatThrownBy(() -> service.getNotifications(USER_ID, NotificationCategory.ALL, null, 20))
            .isInstanceOfSatisfying(NotificationBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(NotificationErrorCode.REQUESTER_NOT_ACTIVE));
        verifyNoInteractions(repository);
    }

    @Test
    void 추가_조회한_항목을_제외하고_마지막_반환_ID를_다음_커서로_사용한다() {
        Notification first = notification(105L);
        first.markAsRead(NOW.plusMinutes(1));
        given(repository.findVisibleByUserId(USER_ID, PageRequest.of(0, 3)))
            .willReturn(List.of(first, notification(104L), notification(103L)));

        NotificationListResponse response = service.getNotifications(
            USER_ID, NotificationCategory.ALL, null, 2);

        assertThat(response.items()).extracting(NotificationListResponse.Item::notificationId)
            .containsExactly(105L, 104L);
        assertThat(response.items().getFirst()).isEqualTo(new NotificationListResponse.Item(
            105L, "CHAT_MESSAGE", "저장된 제목", "저장된 본문", "CHAT_ROOM", 33L,
            NOW.plusMinutes(1), NOW));
        assertThat(response.pageInfo()).isEqualTo(new NotificationListResponse.PageInfo(104L, true));
        verify(repository).findVisibleByUserId(USER_ID, PageRequest.of(0, 3));
        verifyNoMoreInteractions(repository);
    }

    @Test
    void 조회_결과가_요청_크기와_같으면_다음_커서를_반환하지_않는다() {
        given(repository.findVisibleByUserId(USER_ID, PageRequest.of(0, 3)))
            .willReturn(List.of(notification(105L), notification(104L)));

        NotificationListResponse response = service.getNotifications(
            USER_ID, NotificationCategory.ALL, null, 2);

        assertThat(response.items()).extracting(NotificationListResponse.Item::notificationId)
            .containsExactly(105L, 104L);
        assertThat(response.pageInfo()).isEqualTo(new NotificationListResponse.PageInfo(null, false));
    }

    @Test
    void 조회_결과가_비어_있으면_빈_목록과_마지막_페이지_정보를_반환한다() {
        given(repository.findVisibleByUserIdAndIdLessThan(USER_ID, 100L, PageRequest.of(0, 21)))
            .willReturn(List.of());

        NotificationListResponse response = service.getNotifications(
            USER_ID, NotificationCategory.ALL, 100L, 20);

        assertThat(response.items()).isEmpty();
        assertThat(response.pageInfo()).isEqualTo(new NotificationListResponse.PageInfo(null, false));
    }

    @Test
    void 크기가_1이면_한_건만_반환하고_그_ID를_다음_커서로_사용한다() {
        given(repository.findVisibleByUserId(USER_ID, PageRequest.of(0, 2)))
            .willReturn(List.of(notification(105L), notification(104L)));

        NotificationListResponse response = service.getNotifications(
            USER_ID, NotificationCategory.ALL, null, 1);

        assertThat(response.items()).extracting(NotificationListResponse.Item::notificationId)
            .containsExactly(105L);
        assertThat(response.pageInfo()).isEqualTo(new NotificationListResponse.PageInfo(105L, true));
    }

    @Test
    void 카테고리_후속_조회는_유형과_커서를_함께_전달한다() {
        given(repository.findVisibleByUserIdAndTypesAndIdLessThan(
            USER_ID, List.of(NotificationType.CHAT_MESSAGE), 100L, PageRequest.of(0, 21)))
            .willReturn(List.of(notification(95L)));

        NotificationListResponse response = service.getNotifications(
            USER_ID, NotificationCategory.CHAT, 100L, 20);

        assertThat(response.items()).extracting(NotificationListResponse.Item::notificationId)
            .containsExactly(95L);
        assertThat(response.pageInfo()).isEqualTo(new NotificationListResponse.PageInfo(null, false));
        verify(repository).findVisibleByUserIdAndTypesAndIdLessThan(
            USER_ID, List.of(NotificationType.CHAT_MESSAGE), 100L, PageRequest.of(0, 21));
        verifyNoMoreInteractions(repository);
    }

    @Test
    void DB_조회_실패를_빈_목록으로_바꾸지_않고_전달한다() {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("조회 실패");
        given(repository.findVisibleByUserId(USER_ID, PageRequest.of(0, 21))).willThrow(failure);

        assertThatThrownBy(() -> service.getNotifications(USER_ID, NotificationCategory.ALL, null, 20))
            .isSameAs(failure);
    }

    private Notification notification(Long id) {
        Notification notification = new Notification(mock(User.class), NotificationType.CHAT_MESSAGE,
            "저장된 제목", "저장된 본문", "CHAT_ROOM", 33L, NOW);
        ReflectionTestUtils.setField(notification, "id", id);
        return notification;
    }
}
