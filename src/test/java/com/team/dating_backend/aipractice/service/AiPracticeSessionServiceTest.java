package com.team.dating_backend.aipractice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.team.dating_backend.aipractice.dto.request.AiPracticeSessionCreateRequest;
import com.team.dating_backend.aipractice.entity.AiPracticeSession;
import com.team.dating_backend.aipractice.enums.AiPracticeErrorCode;
import com.team.dating_backend.aipractice.exception.AiPracticeBusinessException;
import com.team.dating_backend.aipractice.repository.AiPracticeSessionRepository;
import com.team.dating_backend.common.lock.repository.UserPairLockRepository;
import com.team.dating_backend.security.AuthenticationRequiredException;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.Gender;
import com.team.dating_backend.user.enums.UserStatus;
import com.team.dating_backend.user.repository.UserBlockRepository;
import com.team.dating_backend.user.repository.UserRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AiPracticeSessionServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long TARGET_USER_ID = 2L;

    private AiPracticeSessionRepository sessionRepository;
    private UserRepository userRepository;
    private UserBlockRepository userBlockRepository;
    private UserPairLockRepository userPairLockRepository;
    private AiPracticeSessionService service;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(AiPracticeSessionRepository.class);
        userRepository = mock(UserRepository.class);
        userBlockRepository = mock(UserBlockRepository.class);
        userPairLockRepository = mock(UserPairLockRepository.class);
        service = new AiPracticeSessionService(
            sessionRepository, userRepository, userBlockRepository, userPairLockRepository);
    }

    @Test
    void 활성_차단_관계가_있으면_세션을_생성하거나_재개하지_않는다() {
        given(userRepository.findByIdForUpdate(USER_ID)).willReturn(Optional.of(activeUser()));
        given(userRepository.findByIdForUpdate(TARGET_USER_ID)).willReturn(Optional.of(activeUser()));
        given(userBlockRepository.existsActiveBlockBetween(USER_ID, TARGET_USER_ID))
            .willReturn(true);

        assertThatThrownBy(() -> service.startOrResume(
            USER_ID, new AiPracticeSessionCreateRequest(TARGET_USER_ID)))
            .isInstanceOfSatisfying(
                AiPracticeBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(AiPracticeErrorCode.TARGET_USER_NOT_FOUND));

        verify(sessionRepository, never())
            .findFirstByUserIdAndTargetUserIdAndStatusOrderByIdDesc(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verify(sessionRepository, never()).save(org.mockito.ArgumentMatchers.any(
            AiPracticeSession.class));
    }

    @Test
    void 탈퇴한_상대방과는_AI_연습_세션을_생성하지_않는다() {
        given(userRepository.findByIdForUpdate(USER_ID)).willReturn(Optional.of(activeUser()));
        given(userRepository.findByIdForUpdate(TARGET_USER_ID))
            .willReturn(Optional.of(user(UserStatus.WITHDRAWN)));

        assertThatThrownBy(() -> service.startOrResume(
            USER_ID, new AiPracticeSessionCreateRequest(TARGET_USER_ID)))
            .isInstanceOfSatisfying(
                AiPracticeBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(AiPracticeErrorCode.TARGET_USER_NOT_FOUND));

        verify(sessionRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 탈퇴한_요청자는_AI_연습_세션을_생성할_수_없다() {
        given(userRepository.findByIdForUpdate(USER_ID))
            .willReturn(Optional.of(user(UserStatus.WITHDRAWN)));

        assertThatThrownBy(() -> service.startOrResume(
            USER_ID, new AiPracticeSessionCreateRequest(TARGET_USER_ID)))
            .isInstanceOf(AuthenticationRequiredException.class);

        verify(sessionRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    private User activeUser() {
        return user(UserStatus.ACTIVE);
    }

    private User user(UserStatus status) {
        User user = User.create(
            "사용자", LocalDate.of(2000, 1, 1), Gender.MALE, LocalDateTime.now());
        ReflectionTestUtils.setField(user, "status", status);
        return user;
    }
}
