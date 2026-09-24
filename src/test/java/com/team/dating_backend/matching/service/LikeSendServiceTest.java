package com.team.dating_backend.matching.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.team.dating_backend.matching.entity.Like;
import com.team.dating_backend.matching.enums.LikeErrorCode;
import com.team.dating_backend.matching.enums.LikeStatus;
import com.team.dating_backend.matching.exception.LikeBusinessException;
import com.team.dating_backend.matching.repository.ExistingMatchRepository;
import com.team.dating_backend.matching.repository.LikeRepository;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.UserStatus;
import com.team.dating_backend.user.repository.UserBlockRepository;
import com.team.dating_backend.user.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class LikeSendServiceTest {

    private UserRepository userRepository;
    private UserBlockRepository userBlockRepository;
    private LikeRepository likeRepository;
    private ExistingMatchRepository existingMatchRepository;
    private LikeSendService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        userBlockRepository = mock(UserBlockRepository.class);
        likeRepository = mock(LikeRepository.class);
        existingMatchRepository = mock(ExistingMatchRepository.class);
        service = new LikeSendService(
            userRepository,
            userBlockRepository,
            likeRepository,
            existingMatchRepository);
        User activeSender = user(UserStatus.ACTIVE);
        User activeReceiver = user(UserStatus.ACTIVE);
        given(userRepository.findById(1L)).willReturn(Optional.of(activeSender));
        given(userRepository.findById(2L)).willReturn(Optional.of(activeReceiver));
    }

    @Test
    void 좋아요_전송은_인증된_발신자와_수신자일_때_PENDING으로_저장한다() {
        given(likeRepository.save(any(Like.class)))
            .willAnswer(
                invocation -> {
                    Like like = invocation.getArgument(0);
                    ReflectionTestUtils.setField(like, "id", 10L);
                    return like;
                });

        var response = service.sendLike(2L, 1L);

        assertThat(response.likeId()).isEqualTo(10L);
        assertThat(response.status()).isEqualTo(LikeStatus.PENDING);
        ArgumentCaptor<Like> likeCaptor = ArgumentCaptor.forClass(Like.class);
        verify(likeRepository).save(likeCaptor.capture());
        assertThat(likeCaptor.getValue().getSenderId()).isEqualTo(2L);
        assertThat(likeCaptor.getValue().getReceiverId()).isEqualTo(1L);
        assertThat(likeCaptor.getValue().getResolvedAt()).isNull();
    }

    @Test
    void 반대_방향에_PENDING이_있어도_새_좋아요는_PENDING으로_저장한다() {
        given(likeRepository.save(any(Like.class)))
            .willAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.sendLike(1L, 2L).status()).isEqualTo(LikeStatus.PENDING);

        verify(likeRepository)
            .findFirstBySenderIdAndReceiverIdAndStatusOrderByIdDesc(1L, 2L, LikeStatus.PENDING);
    }

    @Test
    void 같은_방향_PENDING_중복은_409다() {
        given(
            likeRepository.findFirstBySenderIdAndReceiverIdAndStatusOrderByIdDesc(
                1L, 2L, LikeStatus.PENDING))
            .willReturn(Optional.of(new Like(1L, 2L, java.time.LocalDateTime.now())));

        assertError(1L, 2L, LikeErrorCode.DUPLICATE_PENDING_LIKE);
        verify(likeRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void 차단은_어느_방향이든_전송을_막는다() {
        given(userBlockRepository.existsActiveBlockBetween(1L, 2L)).willReturn(true);
        given(userBlockRepository.existsActiveBlockBetween(2L, 1L)).willReturn(true);
        assertError(1L, 2L, LikeErrorCode.MEMBER_NOT_FOUND);
        assertError(2L, 1L, LikeErrorCode.MEMBER_NOT_FOUND);
    }

    @Test
    void 수신자가_정지되거나_탈퇴했으면_전송할_수_없다() {
        for (UserStatus status : new UserStatus[]{UserStatus.SUSPENDED, UserStatus.WITHDRAWN}) {
            User inactiveReceiver = user(status);
            given(userRepository.findById(2L)).willReturn(Optional.of(inactiveReceiver));
            assertError(1L, 2L, LikeErrorCode.MEMBER_NOT_FOUND);
        }
    }

    @Test
    void 발신자가_정지되거나_탈퇴했으면_전송할_수_없다() {
        for (UserStatus status : new UserStatus[]{UserStatus.SUSPENDED, UserStatus.WITHDRAWN}) {
            User inactiveSender = user(status);
            given(userRepository.findById(1L)).willReturn(Optional.of(inactiveSender));
            assertError(1L, 2L, LikeErrorCode.SENDER_NOT_ACTIVE);
        }
    }

    @Test
    void 존재하지_않는_회원에게는_전송할_수_없다() {
        given(userRepository.findById(2L)).willReturn(Optional.empty());
        assertError(1L, 2L, LikeErrorCode.MEMBER_NOT_FOUND);
    }

    @Test
    void 자기_자신에게는_전송할_수_없다() {
        assertError(1L, 1L, LikeErrorCode.SELF_LIKE_NOT_ALLOWED);
        verifyNoInteractions(userBlockRepository, likeRepository, existingMatchRepository);
    }

    @Test
    void 과거_또는_현재_Match가_있으면_좋아요를_허용하지_않는다() {
        given(existingMatchRepository.existsBetween(1L, 2L)).willReturn(true);
        assertError(1L, 2L, LikeErrorCode.MATCH_ALREADY_EXISTS);
        verify(likeRepository, org.mockito.Mockito.never()).save(any());
    }

    private void assertError(Long senderId, Long receiverId, LikeErrorCode errorCode) {
        assertThatThrownBy(() -> service.sendLike(senderId, receiverId))
            .isInstanceOf(LikeBusinessException.class)
            .satisfies(
                exception -> assertThat(((LikeBusinessException) exception).getErrorCode())
                    .isEqualTo(errorCode));
    }

    private User user(UserStatus status) {
        User user = mock(User.class);
        given(user.getStatus()).willReturn(status);
        return user;
    }
}
