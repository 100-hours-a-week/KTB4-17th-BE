package com.team.dating_backend.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.team.dating_backend.chat.entity.ChatRoom;
import com.team.dating_backend.chat.enums.ChatRoomEndReason;
import com.team.dating_backend.chat.enums.ChatRoomStatus;
import com.team.dating_backend.chat.repository.ChatRoomRepository;
import com.team.dating_backend.matching.entity.Match;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChatRoomWithdrawalTerminationServiceTest {

    private ChatRoomRepository chatRoomRepository;
    private ChatRoomWithdrawalTerminationService service;

    @BeforeEach
    void setUp() {
        chatRoomRepository = mock(ChatRoomRepository.class);
        service = new ChatRoomWithdrawalTerminationService(chatRoomRepository);
    }

    @Test
    void 사용자의_모든_활성_채팅방을_USER_WITHDRAWN으로_종료한다() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 10, 1, 12, 0);
        LocalDateTime endedAt = LocalDateTime.of(2026, 10, 14, 12, 0);
        ChatRoom first = room(1L, 2L, createdAt);
        ChatRoom second = room(3L, 1L, createdAt);
        given(chatRoomRepository.findAllActiveForUserForUpdate(1L))
            .willReturn(List.of(first, second));

        service.terminateForUser(1L, endedAt);

        assertThat(List.of(first, second)).allSatisfy(room -> {
            assertThat(room.getStatus()).isEqualTo(ChatRoomStatus.ENDED);
            assertThat(room.getEndReason()).isEqualTo(ChatRoomEndReason.USER_WITHDRAWN);
            assertThat(room.getEndedAt()).isEqualTo(endedAt);
        });
        verify(chatRoomRepository).findAllActiveForUserForUpdate(1L);
    }

    @Test
    void BLOCKED로_종료된_채팅방은_사용자가_탈퇴해도_종료사유와_종료시각을_변경하지_않는다() {
        LocalDateTime firstEndedAt = LocalDateTime.of(2026, 10, 13, 12, 0);
        ChatRoom ended = room(1L, 2L, firstEndedAt.minusDays(1));
        ended.end(ChatRoomEndReason.BLOCKED, firstEndedAt);
        given(chatRoomRepository.findAllActiveForUserForUpdate(1L)).willReturn(List.of(ended));

        service.terminateForUser(1L, firstEndedAt.plusDays(1));

        assertThat(ended.getEndReason()).isEqualTo(ChatRoomEndReason.BLOCKED);
        assertThat(ended.getEndedAt()).isEqualTo(firstEndedAt);
    }

    private ChatRoom room(Long senderId, Long receiverId, LocalDateTime createdAt) {
        return new ChatRoom(new Match(senderId, receiverId, createdAt), createdAt);
    }
}
