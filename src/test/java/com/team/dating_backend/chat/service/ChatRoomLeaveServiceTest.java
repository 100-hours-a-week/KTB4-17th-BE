package com.team.dating_backend.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.team.dating_backend.chat.delegation.service.ChatDelegationParticipantService;
import com.team.dating_backend.chat.dto.response.ChatRoomLeaveResponse;
import com.team.dating_backend.chat.entity.ChatParticipant;
import com.team.dating_backend.chat.entity.ChatRoom;
import com.team.dating_backend.chat.enums.ChatErrorCode;
import com.team.dating_backend.chat.enums.ChatParticipantStatus;
import com.team.dating_backend.chat.enums.ChatRoomEndReason;
import com.team.dating_backend.chat.enums.ChatRoomStatus;
import com.team.dating_backend.chat.exception.ChatBusinessException;
import com.team.dating_backend.chat.repository.ChatRoomRepository;
import com.team.dating_backend.matching.entity.Match;
import com.team.dating_backend.matching.enums.MatchEndReason;
import com.team.dating_backend.matching.enums.MatchStatus;
import com.team.dating_backend.matching.repository.MatchRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ChatRoomLeaveServiceTest {

    private static final Long ROOM_ID = 30L;
    private static final Long USER_ID = 1L;

    private ChatRoomRepository chatRoomRepository;
    private MatchRepository matchRepository;
    private ChatDelegationParticipantService delegationParticipantService;
    private ChatRoomLeaveService service;
    private ChatRoom room;
    private Match userMatch;
    private ChatParticipant leavingParticipant;
    private ChatParticipant otherParticipant;

    @BeforeEach
    void setUp() {
        chatRoomRepository = mock(ChatRoomRepository.class);
        matchRepository = mock(MatchRepository.class);
        delegationParticipantService = mock(ChatDelegationParticipantService.class);
        service = new ChatRoomLeaveService(
            chatRoomRepository, matchRepository, delegationParticipantService);

        LocalDateTime now = LocalDateTime.of(2026, 9, 27, 10, 0);
        userMatch = new Match(USER_ID, 2L, now.minusDays(1));
        ReflectionTestUtils.setField(userMatch, "id", 70L);
        room = new ChatRoom(userMatch, now.minusDays(1));
        ReflectionTestUtils.setField(room, "id", ROOM_ID);
        leavingParticipant = room.addParticipant(USER_ID);
        ReflectionTestUtils.setField(leavingParticipant, "id", 10L);
        otherParticipant = room.addParticipant(2L);
        ReflectionTestUtils.setField(otherParticipant, "id", 20L);
        given(chatRoomRepository.findMatchIdById(ROOM_ID)).willReturn(Optional.of(70L));
        given(matchRepository.findByIdForUpdate(70L)).willReturn(Optional.of(userMatch));
        given(chatRoomRepository.findWithLockById(ROOM_ID)).willReturn(Optional.of(room));
    }

    @Test
    void 나가면_참여자와_방을_종료하고_종료_사유를_기록한다() {
        var response = service.leave(ROOM_ID, USER_ID);

        assertThat(response.participantStatus()).isEqualTo(ChatParticipantStatus.LEFT);
        assertThat(response.roomStatus().name()).isEqualTo("ENDED");
        assertThat(response.endedAt()).isNotNull();
        assertThat(room.getEndReason()).isEqualTo(ChatRoomEndReason.USER_LEFT_CHAT);
        assertThat(leavingParticipant.getLeftAt()).isEqualTo(response.endedAt());
        assertThat(userMatch.getStatus()).isEqualTo(MatchStatus.ENDED);
        assertThat(userMatch.getEndReason()).isEqualTo(MatchEndReason.USER_LEFT_CHAT);
        assertThat(userMatch.getEndedAt()).isEqualTo(response.endedAt());
    }

    @Test
    void 이미_나간_사용자의_재요청은_상태를_다시_변경하지_않는다() {
        var first = service.leave(ROOM_ID, USER_ID);
        var repeated = service.leave(ROOM_ID, USER_ID);

        assertThat(repeated).isEqualTo(first);
        assertThat(room.getEndReason()).isEqualTo(ChatRoomEndReason.USER_LEFT_CHAT);
    }

    @Test
    void 마지막_사용자가_나가면_채팅방을_INACTIVE로_변경한다() {
        ChatRoomLeaveResponse first = service.leave(ROOM_ID, USER_ID);

        ChatRoomLeaveResponse second = service.leave(ROOM_ID, 2L);

        assertThat(first.roomStatus()).isEqualTo(ChatRoomStatus.ENDED);
        assertThat(second.roomStatus()).isEqualTo(ChatRoomStatus.INACTIVE);
        assertThat(otherParticipant.getStatus()).isEqualTo(ChatParticipantStatus.LEFT);
        assertThat(room.getEndedAt()).isEqualTo(first.endedAt());
        assertThat(room.getEndReason()).isEqualTo(ChatRoomEndReason.USER_LEFT_CHAT);
        assertThat(userMatch.getEndedAt()).isEqualTo(first.endedAt());
        assertThat(userMatch.getEndReason()).isEqualTo(MatchEndReason.USER_LEFT_CHAT);
    }

    @Test
    void 양쪽이_나간_후_반복_요청해도_INACTIVE_상태를_그대로_반환한다() {
        service.leave(ROOM_ID, USER_ID);
        service.leave(ROOM_ID, 2L);

        ChatRoomLeaveResponse repeated = service.leave(ROOM_ID, USER_ID);

        assertThat(repeated.roomStatus()).isEqualTo(ChatRoomStatus.INACTIVE);
        assertThat(repeated.participantStatus()).isEqualTo(ChatParticipantStatus.LEFT);
    }

    @Test
    void 이미_다른_사유로_종료된_관계는_최초_종료_정보를_유지한다() {
        LocalDateTime endedAt = LocalDateTime.of(2026, 9, 27, 9, 0);
        userMatch.end(MatchEndReason.BLOCKED, endedAt);
        room.end(ChatRoomEndReason.BLOCKED, endedAt);

        ChatRoomLeaveResponse response = service.leave(ROOM_ID, USER_ID);

        assertThat(response.participantStatus()).isEqualTo(ChatParticipantStatus.LEFT);
        assertThat(response.roomStatus()).isEqualTo(ChatRoomStatus.ENDED);
        assertThat(room.getEndReason()).isEqualTo(ChatRoomEndReason.BLOCKED);
        assertThat(room.getEndedAt()).isEqualTo(endedAt);
        assertThat(userMatch.getEndReason()).isEqualTo(MatchEndReason.BLOCKED);
        assertThat(userMatch.getEndedAt()).isEqualTo(endedAt);
    }

    @Test
    void 참여자가_아닌_사용자는_방을_나갈_수_없다() {
        assertThatThrownBy(() -> service.leave(ROOM_ID, 99L))
            .isInstanceOf(ChatBusinessException.class)
            .satisfies(exception -> assertThat(
                ((ChatBusinessException) exception).getErrorCode())
                .isEqualTo(ChatErrorCode.CHAT_ACCESS_DENIED));
    }
}
