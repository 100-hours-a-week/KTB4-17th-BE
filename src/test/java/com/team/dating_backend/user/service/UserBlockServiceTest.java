package com.team.dating_backend.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.common.enums.ErrorCode;
import com.team.dating_backend.common.exception.RequestValidationException;
import com.team.dating_backend.user.dto.response.UserBlockResult;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.entity.UserBlock;
import com.team.dating_backend.user.enums.UserBlockErrorCode;
import com.team.dating_backend.user.enums.UserStatus;
import com.team.dating_backend.user.exception.UserBlockBusinessException;
import com.team.dating_backend.user.repository.UserBlockRepository;
import com.team.dating_backend.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

class UserBlockServiceTest {

    private static final Long BLOCKER_ID = 1L;
    private static final Long TARGET_ID = 2L;

    private UserRepository userRepository;
    private UserBlockRepository userBlockRepository;
    private UserBlockService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        userBlockRepository = mock(UserBlockRepository.class);
        service = new UserBlockService(userRepository, userBlockRepository);
    }

    @Test
    void 신규_차단을_생성한다() {
        givenUsers(UserStatus.ACTIVE, UserStatus.ACTIVE);
        given(userBlockRepository.save(any(UserBlock.class)))
            .willAnswer(invocation -> invocation.getArgument(0));

        UserBlockResult result = service.block(BLOCKER_ID, TARGET_ID);

        assertThat(result.created()).isTrue();
        assertThat(result.response().targetUserId()).isEqualTo(TARGET_ID);
        ArgumentCaptor<UserBlock> blockCaptor = ArgumentCaptor.forClass(UserBlock.class);
        verify(userBlockRepository).save(blockCaptor.capture());
        UserBlock savedBlock = blockCaptor.getValue();
        assertThat(savedBlock.getBlockerUserId()).isEqualTo(BLOCKER_ID);
        assertThat(savedBlock.getBlockedUserId()).isEqualTo(TARGET_ID);
        assertThat(result.response().blockedAt()).isEqualTo(savedBlock.getBlockedAt());
    }

    @Test
    void 같은_방향의_활성_차단_재요청은_기존_시각을_유지한다() {
        givenUsers(UserStatus.ACTIVE, UserStatus.ACTIVE);
        LocalDateTime originalBlockedAt = LocalDateTime.of(2026, 10, 1, 12, 0);
        UserBlock existing = new UserBlock(BLOCKER_ID, TARGET_ID, originalBlockedAt);
        given(userBlockRepository
            .findByBlockerUserIdAndBlockedUserIdAndUnblockedAtIsNull(BLOCKER_ID, TARGET_ID))
            .willReturn(Optional.of(existing));

        UserBlockResult result = service.block(BLOCKER_ID, TARGET_ID);

        assertThat(result.created()).isFalse();
        assertThat(result.response().blockedAt()).isEqualTo(originalBlockedAt);
        verify(userBlockRepository, never()).save(any(UserBlock.class));
    }

    @Test
    void 인증_회원_ID가_없거나_유효하지_않으면_AUTH_REQUIRED를_반환한다() {
        for (Long userId : new Long[]{null, 0L, -1L}) {
            assertError(userId, TARGET_ID, CommonErrorCode.AUTH_REQUIRED);
        }
        verifyNoInteractions(userBlockRepository);
    }

    @Test
    void 인증_회원이_존재하지_않으면_AUTH_REQUIRED를_반환한다() {
        given(userRepository.findByIdForUpdate(BLOCKER_ID)).willReturn(Optional.empty());

        assertError(BLOCKER_ID, TARGET_ID, CommonErrorCode.AUTH_REQUIRED);
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"ONBOARDING", "SUSPENDED", "WITHDRAWN"})
    void 활성_상태가_아닌_요청자는_차단할_수_없다(UserStatus status) {
        User blocker = user(BLOCKER_ID, status);
        given(userRepository.findByIdForUpdate(BLOCKER_ID)).willReturn(Optional.of(blocker));

        assertError(BLOCKER_ID, TARGET_ID, UserBlockErrorCode.BLOCK_ACCESS_DENIED);
        verify(userRepository, never()).findById(TARGET_ID);
    }

    @Test
    void 자기_자신은_차단할_수_없다() {
        User blocker = user(BLOCKER_ID, UserStatus.ACTIVE);
        given(userRepository.findByIdForUpdate(BLOCKER_ID)).willReturn(Optional.of(blocker));

        assertError(BLOCKER_ID, BLOCKER_ID, UserBlockErrorCode.SELF_BLOCK_NOT_ALLOWED);
    }

    @Test
    void 대상_ID가_양수가_아니면_INVALID_REQUEST를_반환한다() {
        User blocker = user(BLOCKER_ID, UserStatus.ACTIVE);
        given(userRepository.findByIdForUpdate(BLOCKER_ID)).willReturn(Optional.of(blocker));

        for (Long targetId : new Long[]{null, 0L, -1L}) {
            assertThatThrownBy(() -> service.block(BLOCKER_ID, targetId))
                .isInstanceOf(RequestValidationException.class);
        }
        verify(userRepository, never()).findById(0L);
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"ONBOARDING", "WITHDRAWN"})
    void 온보딩_또는_탈퇴한_회원은_차단할_수_없다(UserStatus status) {
        givenUsers(UserStatus.ACTIVE, status);

        assertError(BLOCKER_ID, TARGET_ID, UserBlockErrorCode.USER_NOT_AVAILABLE);
    }

    @Test
    void 존재하지_않는_회원은_차단할_수_없다() {
        User blocker = user(BLOCKER_ID, UserStatus.ACTIVE);
        given(userRepository.findByIdForUpdate(BLOCKER_ID)).willReturn(Optional.of(blocker));
        given(userRepository.findById(TARGET_ID)).willReturn(Optional.empty());

        assertError(BLOCKER_ID, TARGET_ID, UserBlockErrorCode.USER_NOT_AVAILABLE);
    }

    @Test
    void 정지된_회원은_차단할_수_있다() {
        givenUsers(UserStatus.ACTIVE, UserStatus.SUSPENDED);
        given(userBlockRepository.save(any(UserBlock.class)))
            .willAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.block(BLOCKER_ID, TARGET_ID).created()).isTrue();
    }

    private void givenUsers(UserStatus blockerStatus, UserStatus targetStatus) {
        User blocker = user(BLOCKER_ID, blockerStatus);
        User target = user(TARGET_ID, targetStatus);
        given(userRepository.findByIdForUpdate(BLOCKER_ID)).willReturn(Optional.of(blocker));
        given(userRepository.findById(TARGET_ID)).willReturn(Optional.of(target));
    }

    private User user(Long id, UserStatus status) {
        User user = mock(User.class);
        given(user.getId()).willReturn(id);
        given(user.getStatus()).willReturn(status);
        return user;
    }

    private void assertError(Long blockerId, Long targetId, ErrorCode expected) {
        assertThatThrownBy(() -> service.block(blockerId, targetId))
            .isInstanceOfSatisfying(UserBlockBusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }
}
