package com.team.dating_backend.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.team.dating_backend.chat.repository.ChatParticipantDisplayRow;
import com.team.dating_backend.chat.repository.ChatRoomParticipantDisplayRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChatRoomParticipantDisplayServiceTest {

    private ChatRoomParticipantDisplayRepository repository;
    private ChatRoomParticipantDisplayService service;

    @BeforeEach
    void setUp() {
        repository = mock(ChatRoomParticipantDisplayRepository.class);
        service = new ChatRoomParticipantDisplayService(repository);
    }

    @Test
    void 사용자_ID가_없으면_빈_결과를_반환하고_저장소를_호출하지_않는다() {
        assertThat(service.findNicknames(List.of())).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    void 여러_사용자의_닉네임을_ID별로_변환한다() {
        List<Long> userIds = List.of(2L, 3L);
        given(repository.findByUserIds(userIds)).willReturn(List.of(
            new ChatParticipantDisplayRow(2L, "사용자A"),
            new ChatParticipantDisplayRow(3L, "사용자B")));

        Map<Long, String> result = service.findNicknames(userIds);

        assertThat(result).containsExactlyInAnyOrderEntriesOf(Map.of(
            2L, "사용자A",
            3L, "사용자B"));
        verify(repository).findByUserIds(userIds);
    }
}
