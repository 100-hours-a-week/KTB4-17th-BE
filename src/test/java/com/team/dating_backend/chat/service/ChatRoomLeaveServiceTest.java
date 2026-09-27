package com.team.dating_backend.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.team.dating_backend.chat.entity.ChatParticipant;
import com.team.dating_backend.chat.entity.ChatRoom;
import com.team.dating_backend.chat.enums.ChatErrorCode;
import com.team.dating_backend.chat.enums.ChatParticipantStatus;
import com.team.dating_backend.chat.enums.ChatRoomEndReason;
import com.team.dating_backend.chat.exception.ChatBusinessException;
import com.team.dating_backend.chat.repository.ChatRoomRepository;
import com.team.dating_backend.matching.entity.Match;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ChatRoomLeaveServiceTest {

    private static final Long ROOM_ID = 30L;
    private static final Long USER_ID = 1L;

    private ChatRoomRepository chatRoomRepository;
    private ChatRoomLeaveService service;
    private ChatRoom room;
    private ChatParticipant leavingParticipant;

    @BeforeEach
    void setUp() {
        chatRoomRepository = mock(ChatRoomRepository.class);
        service = new ChatRoomLeaveService(chatRoomRepository);

        LocalDateTime now = LocalDateTime.of(2026, 9, 27, 10, 0);
        Match match = new Match(USER_ID, 2L, now.minusDays(1));
        ReflectionTestUtils.setField(match, "id", 70L);
        room = new ChatRoom(match, now.minusDays(1));
        ReflectionTestUtils.setField(room, "id", ROOM_ID);
        leavingParticipant = room.addParticipant(USER_ID);
        ReflectionTestUtils.setField(leavingParticipant, "id", 10L);
        ChatParticipant other = room.addParticipant(2L);
        ReflectionTestUtils.setField(other, "id", 20L);
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
    }

    @Test
    void 이미_나간_사용자의_재요청은_상태를_다시_변경하지_않는다() {
        var first = service.leave(ROOM_ID, USER_ID);
        var repeated = service.leave(ROOM_ID, USER_ID);

        assertThat(repeated).isEqualTo(first);
        assertThat(room.getEndReason()).isEqualTo(ChatRoomEndReason.USER_LEFT_CHAT);
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
