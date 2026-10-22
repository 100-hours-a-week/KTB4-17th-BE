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
import com.team.dating_backend.chat.delegation.repository.ChatDelegationMessageRepository;
import com.team.dating_backend.chat.delegation.repository.ChatDelegationRepository;
import com.team.dating_backend.chat.entity.ChatMessage;
import com.team.dating_backend.chat.entity.ChatMessageOutbox;
import com.team.dating_backend.chat.entity.ChatParticipant;
import com.team.dating_backend.chat.entity.ChatRoom;
import com.team.dating_backend.chat.repository.ChatMessageOutboxRepository;
import com.team.dating_backend.chat.repository.ChatMessageRepository;
import com.team.dating_backend.matching.entity.Match;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class ChatDelegationDeliveryServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 22, 12, 0);

    private ChatDelegationMessageRepository delegationMessageRepository;
    private ChatDelegationRepository delegationRepository;
    private ChatMessageRepository chatMessageRepository;
    private ChatMessageOutboxRepository outboxRepository;
    private ChatDelegationDeliveryService service;

    @BeforeEach
    void setUp() {
        delegationMessageRepository = mock(ChatDelegationMessageRepository.class);
        delegationRepository = mock(ChatDelegationRepository.class);
        chatMessageRepository = mock(ChatMessageRepository.class);
        outboxRepository = mock(ChatMessageOutboxRepository.class);
        service = new ChatDelegationDeliveryService(
            delegationMessageRepository, delegationRepository,
            chatMessageRepository, outboxRepository);
        when(chatMessageRepository.save(any())).thenAnswer(invocation -> {
            ChatMessage message = invocation.getArgument(0);
            ReflectionTestUtils.setField(message, "id", 100L);
            return message;
        });
    }

    @Test
    void 같은_AI_답장은_채팅_메시지로_한_번만_저장한다() {
        Fixture fixture = fixture(1);
        LocalDateTime nextActionAt = NOW.plusHours(2);
        fixture.delegation().reschedule(nextActionAt);
        when(delegationMessageRepository.findWithLockById(30L))
            .thenReturn(Optional.of(fixture.message()));
        when(delegationRepository.findWithLockById(20L))
            .thenReturn(Optional.of(fixture.delegation()));

        assertThat(service.deliver(30L, NOW)).isTrue();
        assertThat(service.deliver(30L, NOW.plusSeconds(1))).isFalse();

        assertThat(fixture.message().getStatus()).isEqualTo(ChatDelegationMessageStatus.SENT);
        assertThat(fixture.message().getChatMessage().getClientMessageId())
            .isEqualTo(fixture.message().getRequestId());
        assertThat(fixture.delegation().getNextActionAt()).isEqualTo(nextActionAt);
        verify(chatMessageRepository, times(1)).save(any(ChatMessage.class));
        ArgumentCaptor<ChatMessageOutbox> outboxCaptor = ArgumentCaptor.forClass(ChatMessageOutbox.class);
        verify(outboxRepository).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getChatMessageId()).isEqualTo(100L);
    }

    @Test
    void 세_번째_답장을_저장하면_위임을_완료한다() {
        Fixture fixture = fixture(3);
        when(delegationMessageRepository.findWithLockById(30L))
            .thenReturn(Optional.of(fixture.message()));
        when(delegationRepository.findWithLockById(20L))
            .thenReturn(Optional.of(fixture.delegation()));

        service.deliver(30L, NOW);

        assertThat(fixture.delegation().getStatus()).isEqualTo(ChatDelegationStatus.COMPLETED);
        assertThat(fixture.delegation().getNextActionAt()).isNull();
    }

    private Fixture fixture(int turn) {
        Match match = new Match(1L, 2L, NOW.minusDays(1));
        ChatRoom room = new ChatRoom(match, NOW.minusDays(1));
        ReflectionTestUtils.setField(room, "id", 10L);
        ChatParticipant requester = room.addParticipant(1L);
        ReflectionTestUtils.setField(requester, "id", 11L);
        room.addParticipant(2L);
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
        return new Fixture(delegation, message);
    }

    private record Fixture(
        ChatDelegation delegation,
        ChatDelegationMessage message) {}
}
