package com.team.dating_backend.chat.delegation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.team.dating_backend.chat.delegation.entity.ChatDelegation;
import com.team.dating_backend.chat.delegation.entity.ChatDelegationMessage;
import com.team.dating_backend.chat.delegation.enums.ChatDelegationEndType;
import com.team.dating_backend.chat.delegation.enums.ChatDelegationMessageStatus;
import com.team.dating_backend.chat.delegation.enums.ChatDelegationStatus;
import com.team.dating_backend.chat.delegation.repository.ChatDelegationMessageLockTarget;
import com.team.dating_backend.chat.delegation.repository.ChatDelegationMessageRepository;
import com.team.dating_backend.chat.delegation.repository.ChatDelegationRepository;
import com.team.dating_backend.chat.entity.ChatMessage;
import com.team.dating_backend.chat.entity.ChatMessageOutbox;
import com.team.dating_backend.chat.entity.ChatParticipant;
import com.team.dating_backend.chat.entity.ChatRoom;
import com.team.dating_backend.chat.enums.ChatParticipantStatus;
import com.team.dating_backend.chat.enums.ChatRoomEndReason;
import com.team.dating_backend.chat.enums.ChatRoomStatus;
import com.team.dating_backend.chat.repository.ChatMessageOutboxRepository;
import com.team.dating_backend.chat.repository.ChatMessageRepository;
import com.team.dating_backend.chat.repository.ChatRoomRepository;
import com.team.dating_backend.matching.entity.Match;
import com.team.dating_backend.matching.enums.MatchEndReason;
import com.team.dating_backend.matching.enums.MatchStatus;
import com.team.dating_backend.matching.repository.MatchRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class ChatDelegationDeliveryServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 23, 12, 0);

    private ChatDelegationMessageRepository delegationMessageRepository;
    private ChatDelegationRepository delegationRepository;
    private ChatMessageRepository chatMessageRepository;
    private ChatMessageOutboxRepository outboxRepository;
    private ChatRoomRepository chatRoomRepository;
    private MatchRepository matchRepository;
    private ChatDelegationDeliveryService service;

    @BeforeEach
    void setUp() {
        delegationMessageRepository = mock(ChatDelegationMessageRepository.class);
        delegationRepository = mock(ChatDelegationRepository.class);
        chatMessageRepository = mock(ChatMessageRepository.class);
        outboxRepository = mock(ChatMessageOutboxRepository.class);
        chatRoomRepository = mock(ChatRoomRepository.class);
        matchRepository = mock(MatchRepository.class);
        service = new ChatDelegationDeliveryService(
            delegationMessageRepository, delegationRepository,
            chatMessageRepository, outboxRepository,
            chatRoomRepository, matchRepository,
            new ChatDelegationSchedulePolicy());
        when(chatMessageRepository.save(any())).thenAnswer(invocation -> {
            ChatMessage message = invocation.getArgument(0);
            ReflectionTestUtils.setField(message, "id", 100L);
            return message;
        });
    }

    @Test
    void AI_답장을_전송한_후_24시간_동안_상대방의_응답을_기다린다() {
        Fixture fixture = fixture(1);
        givenLocks(fixture);

        assertThat(service.deliver(30L, NOW)).isTrue();
        assertThat(service.deliver(30L, NOW.plusSeconds(1))).isFalse();

        assertThat(fixture.message().getStatus()).isEqualTo(ChatDelegationMessageStatus.SENT);
        assertThat(fixture.message().getChatMessage().getClientMessageId())
            .isEqualTo(fixture.message().getRequestId());
        assertThat(fixture.delegation().getNextActionAt()).isEqualTo(NOW.plusHours(24));
        verify(chatMessageRepository, times(1)).save(any(ChatMessage.class));
        ArgumentCaptor<ChatMessageOutbox> outboxCaptor = ArgumentCaptor
            .forClass(ChatMessageOutbox.class);
        verify(outboxRepository).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getChatMessageId()).isEqualTo(100L);
    }

    @Test
    void 마지막_AI_답장을_전송하면_채팅방과_매칭을_종료한다() {
        Fixture fixture = fixture(3);
        givenLocks(fixture);

        assertThat(service.deliver(30L, NOW)).isTrue();

        assertThat(fixture.delegation().getStatus()).isEqualTo(ChatDelegationStatus.COMPLETED);
        assertThat(fixture.room().getStatus()).isEqualTo(ChatRoomStatus.ENDED);
        assertThat(fixture.room().getEndReason())
            .isEqualTo(ChatRoomEndReason.AI_DELEGATION_COMPLETED);
        assertThat(fixture.userMatch().getStatus()).isEqualTo(MatchStatus.ENDED);
        assertThat(fixture.userMatch().getEndReason())
            .isEqualTo(MatchEndReason.AI_DELEGATION_COMPLETED);
        assertThat(fixture.requester().getStatus()).isEqualTo(ChatParticipantStatus.ACTIVE);
    }

    private void givenLocks(Fixture fixture) {
        ChatDelegationMessageLockTarget target = new ChatDelegationMessageLockTarget(
            20L, 10L, 5L, fixture.message().getTurn());
        when(delegationMessageRepository.findLockTargetById(30L))
            .thenReturn(Optional.of(target));
        when(chatRoomRepository.findWithLockById(10L))
            .thenReturn(Optional.of(fixture.room()));
        when(delegationRepository.findWithLockById(20L))
            .thenReturn(Optional.of(fixture.delegation()));
        when(delegationMessageRepository.findWithLockById(30L))
            .thenReturn(Optional.of(fixture.message()));
        if (fixture.message().getTurn() == 3) {
            when(matchRepository.findByIdForUpdate(5L))
                .thenReturn(Optional.of(fixture.userMatch()));
        }
    }

    private Fixture fixture(int turn) {
        Match userMatch = new Match(1L, 2L, NOW.minusDays(1));
        ReflectionTestUtils.setField(userMatch, "id", 5L);
        ChatRoom room = new ChatRoom(userMatch, NOW.minusDays(1));
        ReflectionTestUtils.setField(room, "id", 10L);
        ChatParticipant requester = room.addParticipant(1L);
        ReflectionTestUtils.setField(requester, "id", 11L);
        ChatParticipant target = room.addParticipant(2L);
        ReflectionTestUtils.setField(target, "id", 12L);
        UUID draftRequestId = UUID.randomUUID();
        ChatDelegation delegation = new ChatDelegation(
            room, requester, ChatDelegationEndType.GENTLE, draftRequestId, 10L);
        ReflectionTestUtils.setField(delegation, "id", 20L);
        delegation.completeDraft(draftRequestId, "a".repeat(32));
        delegation.start("a".repeat(32), NOW.minusHours(1), NOW.minusMinutes(1));
        ChatDelegationMessage message = new ChatDelegationMessage(
            delegation, turn, UUID.randomUUID(), 10L);
        ReflectionTestUtils.setField(message, "id", 30L);
        message.ready(50L, "AI 답장");
        return new Fixture(userMatch, room, requester, delegation, message);
    }

    private record Fixture(
        Match userMatch,
        ChatRoom room,
        ChatParticipant requester,
        ChatDelegation delegation,
        ChatDelegationMessage message) {}
}
