package com.team.dating_backend.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.team.dating_backend.notification.enums.NotificationErrorCode;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.UserStatus;
import com.team.dating_backend.user.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessResourceFailureException;

class NotificationAccessServiceTest {

    private static final Long USER_ID = 1L;

    private UserRepository userRepository;
    private NotificationAccessService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        service = new NotificationAccessService(userRepository);
    }

    @Test
    void ACTIVE_회원은_알림에_접근할_수_있다() {
        User user = mock(User.class);
        given(user.getStatus()).willReturn(UserStatus.ACTIVE);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

        assertThatCode(() -> service.assertActiveUser(USER_ID)).doesNotThrowAnyException();

    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
    void ACTIVE가_아닌_회원은_접근을_거부한다(UserStatus status) {
        User user = mock(User.class);
        given(user.getStatus()).willReturn(status);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

        assertAccessDenied();
    }

    @Test
    void 존재하지_않는_회원은_접근을_거부한다() {
        given(userRepository.findById(USER_ID)).willReturn(Optional.empty());

        assertAccessDenied();
    }

    @Test
    void DB_조회_실패를_회원_없음으로_처리하지_않는다() {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException(null);
        given(userRepository.findById(USER_ID)).willThrow(failure);

        assertThatThrownBy(() -> service.assertActiveUser(USER_ID)).isSameAs(failure);
    }

    private void assertAccessDenied() {
        assertThatThrownBy(() -> service.assertActiveUser(USER_ID))
            .isInstanceOfSatisfying(NotificationBusinessException.class, exception -> {
                assertThat(exception.getErrorCode())
                    .isEqualTo(NotificationErrorCode.REQUESTER_NOT_ACTIVE);
                assertThat(exception.getMessage()).isNull();
            });
    }
}
