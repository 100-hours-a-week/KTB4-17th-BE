package com.team.dating_backend.matching.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.team.dating_backend.chat.service.ChatRoomCreateService;
import com.team.dating_backend.matching.entity.Like;
import com.team.dating_backend.matching.entity.Match;
import com.team.dating_backend.matching.enums.LikeErrorCode;
import com.team.dating_backend.matching.enums.LikeStatus;
import com.team.dating_backend.matching.enums.MatchStatus;
import com.team.dating_backend.matching.exception.LikeBusinessException;
import com.team.dating_backend.matching.repository.LikeRepository;
import com.team.dating_backend.matching.repository.MatchRepository;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.UserStatus;
import com.team.dating_backend.user.repository.UserBlockRepository;
import com.team.dating_backend.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class LikeSendServiceTest {

    private UserRepository userRepository;
    private UserBlockRepository userBlockRepository;
    private LikeRepository likeRepository;
    private MatchRepository matchRepository;
    private ChatRoomCreateService chatRoomCreateService;
    private LikeSendService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        userBlockRepository = mock(UserBlockRepository.class);
        likeRepository = mock(LikeRepository.class);
        matchRepository = mock(MatchRepository.class);
        chatRoomCreateService = mock(ChatRoomCreateService.class);
        service = new LikeSendService(
            userRepository,
            userBlockRepository,
            likeRepository,
            matchRepository,
            chatRoomCreateService);
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
        verify(matchRepository, never()).save(any(Match.class));
        verify(chatRoomCreateService, never()).createChatRoom(any(), any(), any(), any());
    }

    @Test
    void 반대_방향의_첫_좋아요가_있으면_상태를_MATCHED로_변경하고_첫_발신자_순서로_Match를_생성한다() {
        Like earlierLike = new Like(2L, 1L, LocalDateTime.now().minusDays(1));
        ReflectionTestUtils.setField(earlierLike, "id", 5L);
        given(likeRepository.findFirstBySenderIdAndReceiverIdAndStatusOrderByIdAsc(
            2L, 1L, LikeStatus.PENDING)).willReturn(Optional.of(earlierLike));
        given(likeRepository.save(any(Like.class)))
            .willAnswer(invocation -> {
                Like savedLike = invocation.getArgument(0);
                ReflectionTestUtils.setField(savedLike, "id", 10L);
                return savedLike;
            });
        given(matchRepository.save(any(Match.class)))
            .willAnswer(invocation -> {
                Match match = invocation.getArgument(0);
                ReflectionTestUtils.setField(match, "id", 30L);
                return match;
            });

        var response = service.sendLike(1L, 2L);

        assertThat(response.likeId()).isEqualTo(10L);
        assertThat(response.status()).isEqualTo(LikeStatus.MATCHED);
        ArgumentCaptor<Like> laterLikeCaptor = ArgumentCaptor.forClass(Like.class);
        verify(likeRepository).save(laterLikeCaptor.capture());
        Like laterLike = laterLikeCaptor.getValue();
        assertThat(earlierLike.getStatus()).isEqualTo(LikeStatus.MATCHED);
        assertThat(laterLike.getStatus()).isEqualTo(LikeStatus.MATCHED);
        assertThat(earlierLike.getResolvedAt()).isNotNull();
        assertThat(laterLike.getResolvedAt()).isEqualTo(earlierLike.getResolvedAt());
        ArgumentCaptor<Match> matchCaptor = ArgumentCaptor.forClass(Match.class);
        verify(matchRepository).save(matchCaptor.capture());
        Match match = matchCaptor.getValue();
        assertThat(match.getSenderId()).isEqualTo(2L);
        assertThat(match.getReceiverId()).isEqualTo(1L);
        assertThat(match.getStatus()).isEqualTo(MatchStatus.ACTIVE);
        assertThat(match.getMatchedAt()).isEqualTo(earlierLike.getResolvedAt());
        verify(chatRoomCreateService).createChatRoom(
            30L, 2L, 1L, match.getMatchedAt());
    }

    @Test
    void 반대_방향에_PENDING이_없으면_MATCH를_생성하지_않는다() {
        given(likeRepository.save(any(Like.class)))
            .willAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.sendLike(1L, 2L).status()).isEqualTo(LikeStatus.PENDING);

        verify(likeRepository)
            .findFirstBySenderIdAndReceiverIdAndStatusOrderByIdDesc(1L, 2L, LikeStatus.PENDING);
        verify(likeRepository)
            .findFirstBySenderIdAndReceiverIdAndStatusOrderByIdAsc(2L, 1L, LikeStatus.PENDING);
        verify(matchRepository, never()).save(any(Match.class));
        verify(chatRoomCreateService, never()).createChatRoom(any(), any(), any(), any());
    }

    @Test
    void 채팅방_생성_중_예외가_발생하면_예외를_전파한다() {
        Like earlierLike = new Like(2L, 1L, LocalDateTime.now().minusDays(1));
        given(likeRepository.findFirstBySenderIdAndReceiverIdAndStatusOrderByIdAsc(
            2L, 1L, LikeStatus.PENDING)).willReturn(Optional.of(earlierLike));
        given(likeRepository.save(any(Like.class)))
            .willAnswer(invocation -> invocation.getArgument(0));
        given(matchRepository.save(any(Match.class)))
            .willAnswer(invocation -> {
                Match match = invocation.getArgument(0);
                ReflectionTestUtils.setField(match, "id", 30L);
                return match;
            });
        IllegalStateException failure = new IllegalStateException("chat room creation failed");
        org.mockito.Mockito.doThrow(failure)
            .when(chatRoomCreateService)
            .createChatRoom(any(), any(), any(), any());

        assertThatThrownBy(() -> service.sendLike(1L, 2L)).isSameAs(failure);
    }

    @Test
    void 같은_방향_PENDING_중복은_409다() {
        given(
            likeRepository.findFirstBySenderIdAndReceiverIdAndStatusOrderByIdDesc(
                1L, 2L, LikeStatus.PENDING))
            .willReturn(Optional.of(new Like(1L, 2L, LocalDateTime.now())));

        assertError(1L, 2L, LikeErrorCode.DUPLICATE_PENDING_LIKE);
        verify(likeRepository, never()).save(any());
        verify(matchRepository, never()).save(any(Match.class));
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
        verifyNoInteractions(
            userBlockRepository, likeRepository, matchRepository, chatRoomCreateService);
    }

    @Test
    void 과거_또는_현재_Match가_있으면_좋아요를_허용하지_않는다() {
        given(matchRepository.existsBetween(1L, 2L)).willReturn(true);
        assertError(1L, 2L, LikeErrorCode.MATCH_ALREADY_EXISTS);
        verify(likeRepository, never()).save(any());
        verify(matchRepository, never()).save(any(Match.class));
        verify(chatRoomCreateService, never()).createChatRoom(any(), any(), any(), any());
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
