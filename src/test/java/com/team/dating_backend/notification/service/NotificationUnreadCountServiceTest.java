package com.team.dating_backend.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.team.dating_backend.notification.enums.NotificationErrorCode;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class NotificationUnreadCountServiceTest {

    private static final Long USER_ID = 7L;

    private NotificationAccessService accessService;
    private NotificationRepository repository;
    private NotificationUnreadCountService service;

    @BeforeEach
    void setUp() {
        accessService = mock(NotificationAccessService.class);
        repository = mock(NotificationRepository.class);
        service = new NotificationUnreadCountService(accessService, repository);
    }

    @Test
    void 접근_검증이_실패하면_미읽음_개수를_집계하지_않는다() {
        willThrow(new NotificationBusinessException(NotificationErrorCode.REQUESTER_NOT_ACTIVE))
            .given(accessService).assertActiveUser(USER_ID);

        assertThatThrownBy(() -> service.getUnreadCount(USER_ID))
            .isInstanceOfSatisfying(NotificationBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(NotificationErrorCode.REQUESTER_NOT_ACTIVE));
        verifyNoInteractions(repository);
    }

    @Test
    void DB_집계_실패를_0으로_바꾸지_않고_원래_예외를_전달한다() {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("집계 실패");
        given(repository.countVisibleUnreadByUserId(USER_ID)).willThrow(failure);

        assertThatThrownBy(() -> service.getUnreadCount(USER_ID)).isSameAs(failure);
    }
}
