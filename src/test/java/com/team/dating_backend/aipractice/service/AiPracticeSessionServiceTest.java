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
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.repository.UserBlockRepository;
import com.team.dating_backend.user.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AiPracticeSessionServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long TARGET_USER_ID = 2L;

    private AiPracticeSessionRepository sessionRepository;
    private UserRepository userRepository;
    private UserBlockRepository userBlockRepository;
    private AiPracticeSessionService service;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(AiPracticeSessionRepository.class);
        userRepository = mock(UserRepository.class);
        userBlockRepository = mock(UserBlockRepository.class);
        service = new AiPracticeSessionService(
            sessionRepository, userRepository, userBlockRepository);
    }

    @Test
    void 활성_차단_관계가_있으면_세션을_생성하거나_재개하지_않는다() {
        given(userRepository.findByIdForUpdate(USER_ID)).willReturn(Optional.of(mock(User.class)));
        given(userRepository.existsById(TARGET_USER_ID)).willReturn(true);
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
}
