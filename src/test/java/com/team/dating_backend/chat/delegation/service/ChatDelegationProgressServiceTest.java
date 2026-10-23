package com.team.dating_backend.chat.delegation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.team.dating_backend.chat.delegation.dto.response.ChatDelegationProgressResponse;
import com.team.dating_backend.chat.delegation.entity.ChatDelegation;
import com.team.dating_backend.chat.delegation.entity.ChatDelegationMessage;
import com.team.dating_backend.chat.delegation.enums.ChatDelegationEndType;
import com.team.dating_backend.chat.delegation.repository.ChatDelegationMessageRepository;
import com.team.dating_backend.chat.delegation.repository.ChatDelegationRepository;
import com.team.dating_backend.chat.entity.ChatMessage;
import com.team.dating_backend.chat.entity.ChatParticipant;
import com.team.dating_backend.chat.entity.ChatRoom;
import com.team.dating_backend.chat.enums.ChatErrorCode;
import com.team.dating_backend.chat.exception.ChatBusinessException;
import com.team.dating_backend.matching.entity.Match;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ChatDelegationProgressServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 22, 12, 0);

    private ChatDelegationRepository delegationRepository;
    private ChatDelegationMessageRepository messageRepository;
    private ChatDelegationProgressService service;
    private ChatDelegation delegation;

    @BeforeEach
    void setUp() {
        delegationRepository = mock(ChatDelegationRepository.class);
        messageRepository = mock(ChatDelegationMessageRepository.class);
        service = new ChatDelegationProgressService(delegationRepository, messageRepository);
        delegation = delegation();
        when(delegationRepository.findById(20L)).thenReturn(Optional.of(delegation));
    }

    @Test
    void 요청자에게_턴별_답장_전송_상태를_반환한다() {
        delegation.complete();
        ChatDelegationMessage sent = message(1);
        ChatMessage chatMessage = new ChatMessage(
            delegation.getChatRoom(), delegation.getRequesterParticipant(),
            sent.getRequestId(), "전송된 답장", NOW);
        sent.ready(50L, "전송된 답장");
        sent.markSent(chatMessage);
        when(messageRepository.findByDelegationIdOrderByTurn(20L))
            .thenReturn(List.of(sent, message(2)));

        ChatDelegationProgressResponse response = service.getProgress(10L, 20L, 1L);

        assertThat(response.isBlockPromptRequired()).isTrue();
        assertThat(response.targetUserId()).isEqualTo(2L);
        assertThat(response.turns()).containsExactly(
            new ChatDelegationProgressResponse.TurnProgress(1, true, NOW),
            new ChatDelegationProgressResponse.TurnProgress(2, false, null),
            new ChatDelegationProgressResponse.TurnProgress(3, false, null));
    }

    @Test
    void 요청자가_아니면_진행_상태를_조회할_수_없다() {
        assertThatThrownBy(() -> service.getProgress(10L, 20L, 2L))
            .isInstanceOf(ChatBusinessException.class)
            .satisfies(exception -> assertThat(
                ((ChatBusinessException) exception).getErrorCode())
                .isEqualTo(ChatErrorCode.CHAT_ACCESS_DENIED));
    }

    private ChatDelegation delegation() {
        Match match = new Match(1L, 2L, NOW.minusDays(1));
        ChatRoom room = new ChatRoom(match, NOW.minusDays(1));
        ReflectionTestUtils.setField(room, "id", 10L);
        ChatParticipant requester = room.addParticipant(1L);
        ReflectionTestUtils.setField(requester, "id", 11L);
        room.addParticipant(2L);
        UUID requestId = UUID.randomUUID();
        ChatDelegation result = new ChatDelegation(
            room, requester, ChatDelegationEndType.GENTLE, requestId, 10L);
        ReflectionTestUtils.setField(result, "id", 20L);
        result.completeDraft(requestId, "a".repeat(32));
        result.start("a".repeat(32), NOW.minusHours(1), NOW.plusHours(1));
        return result;
    }

    private ChatDelegationMessage message(int turn) {
        return new ChatDelegationMessage(delegation, turn, UUID.randomUUID(), 10L);
    }
}
