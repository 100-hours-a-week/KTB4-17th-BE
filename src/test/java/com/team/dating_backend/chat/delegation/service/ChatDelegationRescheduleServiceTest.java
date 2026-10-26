package com.team.dating_backend.chat.delegation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.team.dating_backend.chat.delegation.entity.ChatDelegation;
import com.team.dating_backend.chat.delegation.enums.ChatDelegationEndType;
import com.team.dating_backend.chat.delegation.repository.ChatDelegationRepository;
import com.team.dating_backend.chat.entity.ChatMessage;
import com.team.dating_backend.chat.entity.ChatParticipant;
import com.team.dating_backend.chat.entity.ChatRoom;
import com.team.dating_backend.matching.entity.Match;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ChatDelegationRescheduleServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 20, 12, 0);
    private static final LocalDateTime ORIGINAL_SCHEDULE = NOW.plusHours(1);
    private static final String DRAFT_ID = "a".repeat(32);

    private ChatDelegationRepository delegationRepository;
    private ChatDelegationSchedulePolicy schedulePolicy;
    private ChatDelegationRescheduleService service;
    private ChatRoom room;
    private ChatParticipant requester;
    private ChatParticipant target;

    @BeforeEach
    void setUp() {
        delegationRepository = mock(ChatDelegationRepository.class);
        schedulePolicy = mock(ChatDelegationSchedulePolicy.class);
        service = new ChatDelegationRescheduleService(delegationRepository, schedulePolicy);

        Match match = new Match(1L, 2L, NOW.minusDays(1));
        ReflectionTestUtils.setField(match, "id", 5L);
        room = new ChatRoom(match, NOW.minusDays(1));
        ReflectionTestUtils.setField(room, "id", 10L);
        requester = participant(11L, 1L);
        target = participant(12L, 2L);
    }

    @Test
    void 상대방의_최근_메시지에_맞춰_실행_시각을_다시_정한다() {
        ChatDelegation delegation = startedDelegation();
        ChatMessage first = message(target, NOW);
        ChatMessage latest = message(target, NOW.plusMinutes(10));
        LocalDateTime firstSchedule = NOW.plusHours(3);
        LocalDateTime latestSchedule = NOW.plusHours(4);
        when(delegationRepository.findWithLockByChatRoomId(10L))
            .thenReturn(Optional.of(delegation));
        when(schedulePolicy.nextActionAt(first, requester.getId(), NOW))
            .thenReturn(firstSchedule);
        when(schedulePolicy.nextActionAt(latest, requester.getId(), NOW.plusMinutes(10)))
            .thenReturn(latestSchedule);

        service.rescheduleForNewMessage(first);
        service.rescheduleForNewMessage(latest);

        assertThat(delegation.getNextActionAt()).isEqualTo(latestSchedule);
        verify(schedulePolicy).nextActionAt(first, requester.getId(), NOW);
        verify(schedulePolicy)
            .nextActionAt(latest, requester.getId(), NOW.plusMinutes(10));
    }

    @Test
    void 마지막_메시지를_요청자가_보낸_경우_예약_시각을_변경하지_않는다() {
        ChatDelegation delegation = startedDelegation();
        when(delegationRepository.findWithLockByChatRoomId(10L))
            .thenReturn(Optional.of(delegation));

        service.rescheduleForNewMessage(message(requester, NOW));

        assertThat(delegation.getNextActionAt()).isEqualTo(ORIGINAL_SCHEDULE);
        verifyNoInteractions(schedulePolicy);
    }

    @Test
    void 진행_중인_위임만_예약_시각을_변경한다() {
        ChatDelegation delegation = awaitingConfirmationDelegation();
        when(delegationRepository.findWithLockByChatRoomId(10L))
            .thenReturn(Optional.of(delegation));

        service.rescheduleForNewMessage(message(target, NOW));

        assertThat(delegation.getNextActionAt()).isNull();
        verifyNoInteractions(schedulePolicy);
    }

    private ChatDelegation startedDelegation() {
        ChatDelegation delegation = awaitingConfirmationDelegation();
        delegation.start(DRAFT_ID, NOW.minusMinutes(1), ORIGINAL_SCHEDULE);
        return delegation;
    }

    private ChatDelegation awaitingConfirmationDelegation() {
        UUID requestId = UUID.randomUUID();
        ChatDelegation delegation = new ChatDelegation(
            room, requester, ChatDelegationEndType.GENTLE, requestId, 101L);
        ReflectionTestUtils.setField(delegation, "id", 20L);
        delegation.completeDraft(requestId, DRAFT_ID);
        return delegation;
    }

    private ChatParticipant participant(Long id, Long userId) {
        ChatParticipant participant = room.addParticipant(userId);
        ReflectionTestUtils.setField(participant, "id", id);
        return participant;
    }

    private ChatMessage message(ChatParticipant sender, LocalDateTime sentAt) {
        return new ChatMessage(room, sender, UUID.randomUUID(), "메시지", sentAt);
    }
}
